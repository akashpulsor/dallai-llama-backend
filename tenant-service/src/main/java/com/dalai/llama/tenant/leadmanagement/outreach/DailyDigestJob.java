package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms.MailableFilm;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore.PendingIntent;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Rule 15's daily digest (18:30 IST): each recipient with queued mail and nothing delivered today
 * gets one email from Dalai Llama with up to {@code digestMaxCards} films. Anything beyond that, or
 * queued for someone already emailed today, waits for the next run. Also purges raw recipient data
 * past retention (rule 17). */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyDigestJob {

    private final OutreachStore store;
    private final MailableFilms mailableFilms;
    private final OutreachComposer composer;
    private final PlatformMailer mailer;
    private final OutreachProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public record RunSummary(int digestsSent, int expired) {
    }

    @Scheduled(cron = "${outreach.digest-cron:0 0 13 * * *}", zone = "UTC")
    public void scheduled() {
        RunSummary summary = run();
        int purged = store.purgeBefore(clock.instant().minus(Duration.ofDays(properties.retentionDays())));
        log.info("Outreach digest: {} sent, {} expired, {} rows purged", summary.digestsSent(), summary.expired(), purged);
    }

    public RunSummary run() {
        Instant now = clock.instant();
        int expired = store.expirePendingBefore(now.minus(Duration.ofDays(properties.pendingExpiryDays())));
        int sent = 0;
        for (String recipient : store.recipientsWithPending()) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(tx -> digestFor(recipient, now)))) sent++;
            } catch (RuntimeException e) {
                log.warn("Digest failed for one recipient: {}", e.getMessage());
            }
        }
        return new RunSummary(sent, expired);
    }

    private boolean digestFor(String recipient, Instant now) {
        List<PendingIntent> pending = store.pendingFor(recipient);
        List<OutreachComposer.DigestCard> cards = new ArrayList<>();
        List<UUID> used = new ArrayList<>();
        List<UUID> gone = new ArrayList<>();
        Set<UUID> films = new HashSet<>();
        for (PendingIntent intent : pending) {
            if (cards.size() == properties.digestMaxCards()) break;
            Optional<MailableFilm> film = Optional.ofNullable(intent.showcaseItemId()).flatMap(mailableFilms::byItemId);
            // A film that stopped being mailable (hidden, consent withdrawn) is dropped, not sent.
            if (film.isEmpty()) {
                gone.add(intent.id());
            } else {
                used.add(intent.id());
                if (films.add(film.get().itemId())) cards.add(new OutreachComposer.DigestCard(film.get(), intent.template()));
            }
        }
        store.markIntents(gone, OutreachStore.IntentStatus.EXPIRED, null, now);
        if (cards.isEmpty()) return false;

        String email = pending.stream().map(PendingIntent::email).filter(e -> e != null).reduce((a, b) -> b).orElse(null);
        if (email == null) return false;
        String name = pending.stream().map(PendingIntent::name).filter(n -> n != null && !n.isBlank()).findFirst().orElse(null);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Optional<OutreachStore.Delivery> slot = store.claimDay(recipient, today, "DIGEST", null, email, "digest");
        if (slot.isEmpty()) return false;

        OutreachComposer.Mail mail = composer.digest(cards, name, slot.get());
        if (!mailer.send(new PlatformMailer.Mail(email, mail.subject(), mail.text(), mail.html(), mail.headers()))) {
            store.releaseDay(slot.get().id());
            return false;
        }
        store.markSent(slot.get().id(), mail.subject());
        store.markIntents(used, OutreachStore.IntentStatus.DIGESTED, slot.get().id(), now);
        return true;
    }
}
