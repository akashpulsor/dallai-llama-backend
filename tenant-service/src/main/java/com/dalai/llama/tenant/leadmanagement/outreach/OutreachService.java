package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.leadmanagement.audience.AudienceStore;
import com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateStore.EmailTemplate;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms.MailableFilm;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.AudienceSendRequest;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.AudienceSendResult;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.MailableFilmView;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.Outcome;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.OverviewView;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.PackBought;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.PreviewRequest;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.PreviewView;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.Recipient;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.RecipientResult;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.SendRequest;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachDtos.SendResult;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore.IntentStatus;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore.NewIntent;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore.Origin;
import com.dalai.llama.tenant.service.client.BillingServiceClient;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A creator mailing brands their work (rules 9, 15, 16, 26). Each recipient is checked in order:
 * suppressed → this creator's cooldown → allowance; the mail is then queued and handed to the
 * {@link IntentDispatcher}: right away for typed recipients, by {@link OutreachDispatchJob} for an
 * audience. Either way it goes now if the recipient has had nothing today, else into their digest. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutreachService {

    static final String MAIL_PACK = "OUTREACH_MAIL_PACK";
    static final int MAX_AUDIENCE_SEND = 1_000;

    private final OutreachStore store;
    private final MailAllowance allowance;
    private final MailableFilms mailableFilms;
    private final OutreachComposer composer;
    private final RecipientHasher hasher;
    private final IntentDispatcher dispatcher;
    private final EmailTemplateService templates;
    private final AudienceStore audiences;
    private final CreatorPublicProfileRepository profileRepository;
    private final com.dalai.llama.tenant.showcase.service.CreatorProfileService profileService;
    private final BillingServiceClient billing;
    private final OutreachProperties properties;
    private final Clock clock;

    public OverviewView overview(UUID tenantId) {
        return new OverviewView(allowance.view(tenantId), billing.addonCatalog(tenantId).stream()
                .filter(o -> MAIL_PACK.equals(o.code())).toList(),
                mailableFilms.forCreator(tenantId).stream().map(OutreachService::toView).toList(),
                properties.maxRecipientsPerSend(), MAX_AUDIENCE_SEND, properties.cooldownDays());
    }

    public PreviewView preview(UUID tenantId, PreviewRequest request) {
        CreatorPublicProfile profile = profile(tenantId);
        EmailTemplate template = templates.resolve(tenantId, request.templateId(), request.template());
        OutreachComposer.Mail mail = composer.creatorMail(template, films(tenantId, template.layout(), request.publicId()),
                profile.getDisplayName(), profile.getHandle(), request.recipientName(), request.note(), null);
        return new PreviewView(mail.subject(), mail.text(), mail.html());
    }

    @Transactional
    public SendResult send(UUID tenantId, SendRequest request) {
        if (!hasher.configured()) throw new IllegalStateException(OUTREACH_OFF);
        if (request.recipients().size() > properties.maxRecipientsPerSend()) {
            throw new IllegalArgumentException("Send to at most " + properties.maxRecipientsPerSend() + " people at a time");
        }
        profile(tenantId);
        EmailTemplate template = templates.resolve(tenantId, request.templateId(), request.template());
        List<MailableFilm> films = films(tenantId, template.layout(), request.publicId());
        Instant now = clock.instant();
        allowance.lock(tenantId);

        Set<String> seen = new HashSet<>();
        List<RecipientResult> results = new ArrayList<>();
        for (Recipient r : request.recipients()) {
            String hash = hasher.hash(r.email());
            Outcome outcome = !seen.add(hash) ? Outcome.DUPLICATE : deliver(tenantId, template, films, request.note(), r, hash, now);
            results.add(new RecipientResult(RecipientHasher.normalise(r.email()), outcome));
        }
        return new SendResult(results, allowance.view(tenantId));
    }

    private Outcome deliver(UUID tenantId, EmailTemplate template, List<MailableFilm> films, String note, Recipient r,
                            String hash, Instant now) {
        Optional<Outcome> refused = refusal(tenantId, hash, now);
        if (refused.isPresent()) return refused.get();
        Optional<MailAllowance.Charge> charge = allowance.take(tenantId);
        if (charge.isEmpty()) return Outcome.OVER_ALLOWANCE;

        String email = RecipientHasher.normalise(r.email());
        UUID itemId = films.get(0).itemId();
        UUID intent = store.addIntent(new NewIntent(tenantId, hash, email, r.name(), r.company(), template.layout(), Origin.CREATOR,
                itemId, note, charge.get().packId(), template.id(), null, null), IntentStatus.QUEUED, now);
        IntentDispatcher.Result result = dispatcher.dispatch(new OutreachStore.QueuedIntent(intent, tenantId, hash, email, r.name(),
                template.layout(), itemId, note, charge.get().packId(), template.id()));
        return switch (result) {
            case SENT -> Outcome.SENT;
            case WAITING_FOR_DIGEST -> Outcome.QUEUED;
            case SUPPRESSED -> Outcome.SUPPRESSED;
            case UNDELIVERABLE -> Outcome.UNDELIVERABLE;
        };
    }

    /** Rule 26: every lead in the audience gets one email, picked by {@link AudienceStore#recipients};
     * accepted ones are queued for the dispatch job (sent within a minute or two). */
    @Transactional
    public AudienceSendResult sendToAudience(UUID tenantId, AudienceSendRequest request) {
        if (!hasher.configured()) throw new IllegalStateException(OUTREACH_OFF);
        if (!audiences.owns(tenantId, request.audienceId())) throw new IllegalArgumentException("Unknown audience");
        profile(tenantId);
        EmailTemplate template = templates.resolve(tenantId, request.templateId(), request.template());
        List<MailableFilm> films = films(tenantId, template.layout(), request.publicId());
        Instant now = clock.instant();
        int members = audiences.memberCount(request.audienceId());
        List<AudienceStore.Recipient> recipients = audiences.recipients(tenantId, request.audienceId(), MAX_AUDIENCE_SEND);
        allowance.lock(tenantId);

        int queued = 0, suppressed = 0, cooldown = 0, over = 0, duplicates = 0;
        Set<String> seen = new HashSet<>();
        for (AudienceStore.Recipient r : recipients) {
            String hash = hasher.hash(r.email());
            if (!seen.add(hash)) { duplicates++; continue; }
            Optional<Outcome> refused = refusal(tenantId, hash, now);
            if (refused.isPresent()) {
                if (refused.get() == Outcome.SUPPRESSED) suppressed++; else cooldown++;
                continue;
            }
            Optional<MailAllowance.Charge> charge = allowance.take(tenantId);
            if (charge.isEmpty()) { over++; continue; }
            store.addIntent(new NewIntent(tenantId, hash, RecipientHasher.normalise(r.email()), r.name(), r.company(),
                    template.layout(), Origin.CREATOR, films.get(0).itemId(), request.note(), charge.get().packId(), template.id(),
                    request.audienceId(), r.leadId()), IntentStatus.QUEUED, now);
            queued++;
        }
        int unreachable = Math.max(0, Math.min(members, MAX_AUDIENCE_SEND) - recipients.size());
        return new AudienceSendResult(queued, suppressed, cooldown, over, duplicates, unreachable, allowance.view(tenantId));
    }

    /** Suppression first, then this creator's cooldown (rules 15, 30). */
    private Optional<Outcome> refusal(UUID tenantId, String hash, Instant now) {
        if (store.isSuppressed(hash)) return Optional.of(Outcome.SUPPRESSED);
        if (store.contactedWithin(tenantId, hash, now.minus(Duration.ofDays(properties.cooldownDays())))) {
            return Optional.of(Outcome.COOLDOWN);
        }
        return Optional.empty();
    }

    /** Automatic picks and follower notices (Phase D): digest only, never the creator's allowance. */
    @Transactional
    public boolean enqueue(UUID tenantId, String email, String name, OutreachTemplate template, Origin origin, UUID itemId) {
        if (!hasher.configured()) return false;
        String hash = hasher.hash(email);
        Instant now = clock.instant();
        if (store.isSuppressed(hash)) return false;
        if (store.contactedWithin(tenantId, hash, now.minus(Duration.ofDays(properties.cooldownDays())))) return false;
        store.addIntent(new NewIntent(tenantId, hash, RecipientHasher.normalise(email), name, null, template, origin, itemId,
                null, null, null, null, null), IntentStatus.PENDING, now);
        return true;
    }

    /** Mails delivered, clicks and requests from mail over the last 30 days, plus followers. */
    public OutreachDtos.ReachView reach(UUID tenantId) {
        OutreachStore.Reach reach = store.reach(tenantId, clock.instant().minus(Duration.ofDays(30)));
        return new OutreachDtos.ReachView(reach.mailsDelivered(), reach.clicks(), reach.requestsFromMail(),
                profile(tenantId).getFollowerCount());
    }

    public List<OutreachStore.IntentRow> history(UUID tenantId) {
        return store.recentForCreator(tenantId, clock.instant().minus(Duration.ofDays(properties.retentionDays())));
    }

    /** Billing charges the wallet (and owns the price); the pack is recorded under the same key,
     * so retrying after a timeout neither charges twice nor loses the pack. */
    @Transactional
    public PackBought buyPack(UUID tenantId, String idempotencyKey) {
        String key = tenantId + ":" + idempotencyKey;
        BillingServiceClient.AddonPurchase bought = billing.purchaseAddon(tenantId, MAIL_PACK, key);
        allowance.addPack(tenantId, key, bought.quantity(), bought.price(), bought.currency());
        return new PackBought(bought.quantity(), bought.price(), bought.currency(), allowance.view(tenantId));
    }

    /** One-click unsubscribe from any outreach mail. Unknown tokens are ignored (nothing to say). */
    @Transactional
    public void unsubscribe(String token) {
        store.recipientOfUnsubscribeToken(token).ifPresent(hash -> store.suppress(hash, "UNSUBSCRIBED"));
    }

    @Transactional
    public void suppressByOps(String email) {
        store.suppress(hasher.hash(email), "OPS");
    }

    private List<MailableFilm> films(UUID tenantId, OutreachTemplate layout, String publicId) {
        if (!layout.creatorSendable()) throw new IllegalArgumentException("That template isn't sent by creators");
        if (layout == OutreachTemplate.CREATOR_PORTFOLIO) {
            List<MailableFilm> films = mailableFilms.forCreator(tenantId);
            if (films.isEmpty()) throw new IllegalStateException(NOTHING_MAILABLE);
            return films;
        }
        if (publicId == null) throw new IllegalArgumentException("Choose the film to send");
        return List.of(mailableFilms.byPublicId(tenantId, publicId).orElseThrow(() -> new IllegalStateException(NOTHING_MAILABLE)));
    }

    private static final String OUTREACH_OFF = "Sending to brands isn't switched on yet. Please try again later.";

    private static final String NOTHING_MAILABLE = "Only films made on Dalai Llama that your client fully paid for and agreed "
            + "to marketing use of can be emailed. Publish one to your profile first.";

    private CreatorPublicProfile profile(UUID tenantId) {
        return profileService.requireProfile(tenantId);
    }

    private static MailableFilmView toView(MailableFilm f) {
        return new MailableFilmView(f.publicId(), f.title(), f.thumbnailUrl(), f.industry(), f.clientLabel());
    }
}
