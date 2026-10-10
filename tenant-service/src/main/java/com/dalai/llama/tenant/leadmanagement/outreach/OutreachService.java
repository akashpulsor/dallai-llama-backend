package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailMessage;
import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailSender;
import com.dalai.llama.tenant.leadmanagement.email.EmailSendResult;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms.MailableFilm;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A creator mailing brands their work (rules 9, 15, 16). Each recipient is checked in order:
 * suppressed → this creator's cooldown → allowance; then the mail goes now if the recipient has had
 * nothing today, else it waits for their next daily digest. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutreachService {

    static final String MAIL_PACK = "OUTREACH_MAIL_PACK";

    private final OutreachStore store;
    private final MailAllowance allowance;
    private final MailableFilms mailableFilms;
    private final OutreachComposer composer;
    private final RecipientHasher hasher;
    private final CreatorEmailSender creatorSender;
    private final CreatorPublicProfileRepository profileRepository;
    private final BillingServiceClient billing;
    private final OutreachProperties properties;
    private final Clock clock;

    public OverviewView overview(UUID tenantId) {
        return new OverviewView(allowance.view(tenantId), billing.addonCatalog(tenantId).stream()
                .filter(o -> MAIL_PACK.equals(o.code())).toList(),
                mailableFilms.forCreator(tenantId).stream().map(OutreachService::toView).toList(),
                properties.maxRecipientsPerSend(), properties.cooldownDays());
    }

    public PreviewView preview(UUID tenantId, PreviewRequest request) {
        CreatorPublicProfile profile = profile(tenantId);
        OutreachComposer.Mail mail = composer.creatorMail(request.template(), films(tenantId, request.template(), request.publicId()),
                profile.getDisplayName(), profile.getHandle(), request.recipientName(), request.note(), null);
        return new PreviewView(mail.subject(), mail.text(), mail.html());
    }

    @Transactional
    public SendResult send(UUID tenantId, SendRequest request) {
        if (request.recipients().size() > properties.maxRecipientsPerSend()) {
            throw new IllegalArgumentException("Send to at most " + properties.maxRecipientsPerSend() + " people at a time");
        }
        CreatorPublicProfile profile = profile(tenantId);
        List<MailableFilm> films = films(tenantId, request.template(), request.publicId());
        Instant now = clock.instant();
        allowance.lock(tenantId);

        Set<String> seen = new HashSet<>();
        List<RecipientResult> results = new ArrayList<>();
        for (Recipient r : request.recipients()) {
            String hash = hasher.hash(r.email());
            Outcome outcome = !seen.add(hash) ? Outcome.DUPLICATE : deliver(tenantId, profile, films, request, r, hash, now);
            results.add(new RecipientResult(RecipientHasher.normalise(r.email()), outcome));
        }
        return new SendResult(results, allowance.view(tenantId));
    }

    private Outcome deliver(UUID tenantId, CreatorPublicProfile profile, List<MailableFilm> films, SendRequest request,
                            Recipient r, String hash, Instant now) {
        if (store.isSuppressed(hash)) return Outcome.SUPPRESSED;
        if (store.contactedWithin(tenantId, hash, now.minus(Duration.ofDays(properties.cooldownDays())))) return Outcome.COOLDOWN;
        Optional<MailAllowance.Charge> charge = allowance.take(tenantId);
        if (charge.isEmpty()) return Outcome.OVER_ALLOWANCE;

        String email = RecipientHasher.normalise(r.email());
        UUID intent = store.addIntent(new NewIntent(tenantId, hash, email, r.name(), r.company(), request.template(),
                Origin.CREATOR, films.get(0).itemId(), request.note(), charge.get().packId()), now);

        Optional<OutreachStore.Delivery> today = store.claimDay(hash, today(), "CREATOR_MAIL", tenantId, email, "pending");
        if (today.isEmpty()) return Outcome.QUEUED;
        OutreachComposer.Mail mail = composer.creatorMail(request.template(), films, profile.getDisplayName(), profile.getHandle(),
                r.name(), request.note(), today.get());
        EmailSendResult sent = creatorSender.send(CreatorEmailMessage.builder()
                .fromCreatorId(tenantId).to(List.of(email)).subject(mail.subject())
                .bodyText(mail.text()).bodyHtml(mail.html()).headers(mail.headers()).build());
        if (!sent.accepted()) {
            // Give the day back; the intent stays queued and the digest delivers it.
            log.warn("Outreach mail from {} not sent now ({}); queued for the digest", tenantId, sent.error());
            store.releaseDay(today.get().id());
            return Outcome.QUEUED;
        }
        store.markSent(today.get().id(), mail.subject());
        store.markIntents(List.of(intent), OutreachStore.IntentStatus.SENT, today.get().id(), now);
        return Outcome.SENT;
    }

    /** Automatic picks and follower notices (Phase D): digest only, never the creator's allowance. */
    @Transactional
    public boolean enqueue(UUID tenantId, String email, String name, OutreachTemplate template, Origin origin, UUID itemId) {
        String hash = hasher.hash(email);
        Instant now = clock.instant();
        if (store.isSuppressed(hash)) return false;
        if (store.contactedWithin(tenantId, hash, now.minus(Duration.ofDays(properties.cooldownDays())))) return false;
        store.addIntent(new NewIntent(tenantId, hash, RecipientHasher.normalise(email), name, null, template, origin, itemId,
                null, null), now);
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

    private List<MailableFilm> films(UUID tenantId, OutreachTemplate template, String publicId) {
        if (!template.creatorSendable()) throw new IllegalArgumentException("That template isn't sent by creators");
        if (template == OutreachTemplate.CREATOR_PORTFOLIO) {
            List<MailableFilm> films = mailableFilms.forCreator(tenantId);
            if (films.isEmpty()) throw new IllegalStateException(NOTHING_MAILABLE);
            return films;
        }
        if (publicId == null) throw new IllegalArgumentException("Choose the film to send");
        return List.of(mailableFilms.byPublicId(tenantId, publicId).orElseThrow(() -> new IllegalStateException(NOTHING_MAILABLE)));
    }

    private static final String NOTHING_MAILABLE = "Only films made on Dalai Llama that your client fully paid for and agreed "
            + "to marketing use of can be emailed. Publish one to your profile first.";

    private CreatorPublicProfile profile(UUID tenantId) {
        return profileRepository.findById(tenantId).orElseThrow(() -> new IllegalStateException("Set up your public profile first"));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static MailableFilmView toView(MailableFilm f) {
        return new MailableFilmView(f.publicId(), f.title(), f.thumbnailUrl(), f.industry(), f.clientLabel());
    }
}
