package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms.MailableFilm;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachComposer.DigestReason;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachComposer.DigestSection;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore.Origin;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore.PendingIntent;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.inspiration.InspirationStore.MailIdea;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Rule 15's digest: mail held for a recipient (they already had today's email) is merged into one
 * email from Dalai Llama, a block per creator (up to {@code digestMaxCards} creators) with what they
 * wrote, their videos (each at most once in the mail) and a button to request a video from them. Runs hourly; each recipient's digest
 * goes out at the hour they tend to open mail ({@link OutreachSendTimes}). Creators beyond the cap,
 * or anyone already emailed today, wait for the next digest, as does everyone left once the daily
 * send cap is reached. Also drops mail held too long (given back to the creator) and purges raw
 * recipient data past retention (rule 17). */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyDigestJob {

    private final OutreachStore store;
    private final MailableFilms mailableFilms;
    private final EmailTemplateStore templates;
    private final MailIdeas mailIdeas;
    private final CreatorPublicProfileRepository profiles;
    private final OutreachComposer composer;
    private final OutreachSendTimes sendTimes;
    private final PlatformMailer mailer;
    private final OutreachProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public record RunSummary(int digestsSent, int expired) {
    }

    private enum Digest { SENT, SKIPPED, DAY_FULL }

    @Scheduled(cron = "${outreach.digest-cron:0 0 * * * *}", zone = "UTC")
    public void scheduled() {
        RunSummary summary = runDue();
        int purged = store.purgeBefore(clock.instant().minus(Duration.ofDays(properties.retentionDays())));
        if (summary.digestsSent() + summary.expired() + purged > 0) {
            log.info("Outreach digest: {} sent, {} expired, {} rows purged", summary.digestsSent(), summary.expired(), purged);
        }
    }

    /** Recipients whose send hour has come today. */
    public RunSummary runDue() {
        int hour = clock.instant().atZone(ZoneOffset.UTC).getHour();
        int platformHour = sendTimes.platformHour();
        return run(recipient -> sendTimes.hourFor(recipient, platformHour) <= hour);
    }

    /** Everyone with held mail, whatever their send hour (ops' "run now"). */
    public RunSummary run() {
        return run(recipient -> true);
    }

    private RunSummary run(Predicate<String> due) {
        Instant now = clock.instant();
        int expired = store.expirePendingBefore(now.minus(Duration.ofDays(properties.pendingExpiryDays())));
        int sent = 0;
        for (String recipient : store.recipientsWithPending()) {
            if (!due.test(recipient)) continue;
            try {
                Digest digest = transactions.execute(tx -> digestFor(recipient, now));
                if (digest == Digest.DAY_FULL) break;
                if (digest == Digest.SENT) sent++;
            } catch (RuntimeException e) {
                log.warn("Digest failed for one recipient: {}", e.getMessage());
            }
        }
        return new RunSummary(sent, expired);
    }

    private Digest digestFor(String recipient, Instant now) {
        List<PendingIntent> pending = store.pendingFor(recipient);
        String name = pending.stream().map(PendingIntent::name).filter(n -> n != null && !n.isBlank()).findFirst().orElse(null);
        Map<UUID, Block> blocks = new LinkedHashMap<>();
        List<UUID> used = new ArrayList<>();
        List<UUID> gone = new ArrayList<>();
        // A video already in this mail isn't shown again, whoever else held it.
        Set<UUID> shown = new HashSet<>();
        for (PendingIntent intent : pending) {
            Block block = blocks.get(intent.tenantId());
            // More creators than fit: theirs waits for the next digest.
            if (block == null && blocks.size() == properties.digestMaxCards()) continue;
            Optional<CreatorPublicProfile> creator = profiles.findById(intent.tenantId());
            OutreachComposer.Content content = creator.map(c -> content(intent, c)).orElse(OutreachComposer.Content.of(List.of()));
            // A film that stopped being mailable (hidden, consent withdrawn) is dropped, not sent.
            if (content.isEmpty()) {
                gone.add(intent.id());
                continue;
            }
            used.add(intent.id());
            if (block == null) blocks.put(intent.tenantId(), block = new Block(creator.get()));
            block.add(intent, content, shown, name);
        }
        store.expire(gone, now);
        List<DigestSection> sections = blocks.values().stream().filter(Block::hasVideos).map(Block::section).toList();
        if (sections.isEmpty()) return Digest.SKIPPED;

        String email = pending.stream().map(PendingIntent::email).filter(e -> e != null).reduce((a, b) -> b).orElse(null);
        if (email == null) return Digest.SKIPPED;
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!store.dayHasRoom(today, properties.dailySendCap())) return Digest.DAY_FULL;
        Optional<OutreachStore.Delivery> slot = store.claimDay(recipient, today, "DIGEST", null, email, "digest");
        if (slot.isEmpty()) return Digest.SKIPPED;

        OutreachComposer.Mail mail = composer.digest(sections, name, slot.get());
        if (!mailer.send(new PlatformMailer.Mail(email, mail.subject(), mail.text(), mail.html(), mail.headers()))) {
            store.releaseDay(slot.get().id());
            return Digest.SKIPPED;
        }
        store.markSent(slot.get().id(), mail.subject());
        store.markIntents(used, OutreachStore.IntentStatus.DIGESTED, slot.get().id(), now);
        return Digest.SENT;
    }

    /** The videos this held mail carries: a portfolio is all of the creator's mailable films;
     * inspiration, the ideas for the creator's industries. */
    private OutreachComposer.Content content(PendingIntent intent, CreatorPublicProfile creator) {
        OutreachTemplate template = intent.template();
        if (template == OutreachTemplate.INSPIRATION) {
            return OutreachComposer.Content.ideas(mailIdeas.forIndustries(creator.getIndustries(), template.maxFilms()));
        }
        if (template == OutreachTemplate.CREATOR_PORTFOLIO) {
            List<MailableFilm> films = mailableFilms.forCreator(intent.tenantId());
            return OutreachComposer.Content.of(films.subList(0, Math.min(films.size(), template.maxFilms())));
        }
        return OutreachComposer.Content.of(Optional.ofNullable(intent.showcaseItemId()).flatMap(mailableFilms::byItemId).stream().toList());
    }

    /** One creator's held mail for this recipient, merged; at most a portfolio's worth of videos. */
    private final class Block {
        private static final int MAX_VIDEOS = OutreachTemplate.CREATOR_PORTFOLIO.maxFilms();

        private final CreatorPublicProfile creator;
        private final List<MailableFilm> films = new ArrayList<>();
        private final List<MailIdea> ideas = new ArrayList<>();
        private DigestReason reason = DigestReason.PICKED_FOR_YOU;
        private String message;

        Block(CreatorPublicProfile creator) {
            this.creator = creator;
        }

        void add(PendingIntent intent, OutreachComposer.Content content, Set<UUID> shown, String recipientName) {
            List<MailableFilm> newFilms = new ArrayList<>();
            for (MailableFilm f : content.films()) {
                if (films.size() + newFilms.size() < MAX_VIDEOS && shown.add(f.itemId())) newFilms.add(f);
            }
            films.addAll(newFilms);
            List<MailIdea> newIdeas = new ArrayList<>();
            // The creator's own work comes first; ideas only fill a block with no film.
            for (MailIdea i : content.ideas()) {
                if (films.isEmpty() && ideas.size() + newIdeas.size() < MAX_VIDEOS && shown.add(i.id())) newIdeas.add(i);
            }
            ideas.addAll(newIdeas);
            OutreachComposer.Content added = new OutreachComposer.Content(newFilms, newIdeas);
            if (intent.origin() == Origin.CREATOR) {
                reason = DigestReason.WROTE_TO_YOU;
                if (message == null && !added.isEmpty()) message = message(intent, added, recipientName);
            } else if (intent.origin() == Origin.FOLLOW && reason == DigestReason.PICKED_FOR_YOU) {
                reason = DigestReason.YOU_FOLLOW;
            }
        }

        boolean hasVideos() {
            return !films.isEmpty() || !ideas.isEmpty();
        }

        /** What the creator wrote: their personal note, else their template's intro. */
        private String message(PendingIntent intent, OutreachComposer.Content content, String recipientName) {
            if (intent.note() != null && !intent.note().isBlank()) return intent.note().trim();
            return Optional.ofNullable(intent.emailTemplateId()).flatMap(templates::find)
                    .or(() -> templates.defaultFor(intent.template()))
                    .map(t -> OutreachComposer.fill(t.intro(), content, creator.getDisplayName(), recipientName))
                    .orElse(null);
        }

        DigestSection section() {
            // Films and ideas never share a block: once a film is in, the ideas step aside.
            OutreachComposer.Content content = films.isEmpty() ? OutreachComposer.Content.ideas(List.copyOf(ideas))
                    : OutreachComposer.Content.of(List.copyOf(films));
            return new DigestSection(creator.getTenantId(), creator.getDisplayName(), creator.getHandle(), reason, message, content);
        }
    }
}
