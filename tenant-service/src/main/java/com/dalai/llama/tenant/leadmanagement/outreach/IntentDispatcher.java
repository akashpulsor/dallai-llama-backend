package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValidator;
import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailMessage;
import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailSender;
import com.dalai.llama.tenant.leadmanagement.email.EmailSendResult;
import com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateStore.EmailTemplate;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms.MailableFilm;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore.IntentStatus;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore.QueuedIntent;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Sends one queued creator mail through the one-a-day slot (rule 15), for typed sends right away
 * and for audience sends from the dispatch job. Re-checks at send time (AUDIENCE_PROVIDER §51):
 * unsubscribed since → dropped; the address can't take mail → failed and the mail given back; the
 * film stopped being mailable → failed and given back. */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentDispatcher {

    public enum Result { SENT, WAITING_FOR_DIGEST, UNDELIVERABLE, SUPPRESSED }

    private final OutreachStore store;
    private final MailableFilms mailableFilms;
    private final EmailTemplateStore templates;
    private final OutreachComposer composer;
    private final CreatorEmailSender creatorSender;
    private final CreatorPublicProfileRepository profileRepository;
    private final ContactPointValidator validator;
    private final Clock clock;

    public Result dispatch(QueuedIntent q) {
        Instant now = clock.instant();
        if (store.isSuppressed(q.recipientHash())) {
            store.markIntents(List.of(q.id()), IntentStatus.EXPIRED, null, now);
            return Result.SUPPRESSED;
        }
        List<MailableFilm> films = films(q);
        Optional<CreatorPublicProfile> profile = profileRepository.findById(q.tenantId());
        if (films.isEmpty() || profile.isEmpty() || !validator.deliverable(q.email())) {
            store.markIntents(List.of(q.id()), IntentStatus.FAILED, null, now);
            store.refundPack(q.mailPackId());
            return Result.UNDELIVERABLE;
        }
        Optional<OutreachStore.Delivery> slot = store.claimDay(q.recipientHash(), LocalDate.now(clock.withZone(ZoneOffset.UTC)),
                "CREATOR_MAIL", q.tenantId(), q.email(), "pending");
        if (slot.isEmpty()) return waitForDigest(q, now);

        OutreachComposer.Mail mail = composer.creatorMail(template(q), films, profile.get().getDisplayName(),
                profile.get().getHandle(), q.name(), q.note(), slot.get());
        EmailSendResult sent = creatorSender.send(CreatorEmailMessage.builder()
                .fromCreatorId(q.tenantId()).to(List.of(q.email())).subject(mail.subject())
                .bodyText(mail.text()).bodyHtml(mail.html()).headers(mail.headers()).build());
        if (!sent.accepted()) {
            // Give the day back; the digest delivers it instead.
            log.warn("Outreach mail from {} not sent now ({}); it goes in the digest", q.tenantId(), sent.error());
            store.releaseDay(slot.get().id());
            return waitForDigest(q, now);
        }
        store.markSent(slot.get().id(), mail.subject());
        store.markIntents(List.of(q.id()), IntentStatus.SENT, slot.get().id(), now);
        return Result.SENT;
    }

    private Result waitForDigest(QueuedIntent q, Instant now) {
        store.markIntents(List.of(q.id()), IntentStatus.PENDING, null, now);
        return Result.WAITING_FOR_DIGEST;
    }

    private List<MailableFilm> films(QueuedIntent q) {
        if (q.template() == OutreachTemplate.CREATOR_PORTFOLIO) return mailableFilms.forCreator(q.tenantId());
        return q.showcaseItemId() == null ? List.of() : mailableFilms.byItemId(q.showcaseItemId()).stream().toList();
    }

    /** The template chosen at send time, even if it was deactivated since. */
    private EmailTemplate template(QueuedIntent q) {
        return Optional.ofNullable(q.emailTemplateId()).flatMap(templates::find)
                .or(() -> templates.defaultFor(q.template()))
                .orElseThrow(() -> new IllegalStateException("No template for " + q.template()));
    }
}
