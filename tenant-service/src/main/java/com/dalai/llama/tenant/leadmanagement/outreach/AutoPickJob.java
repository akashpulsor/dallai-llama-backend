package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.leadmanagement.brand.BrandContact;
import com.dalai.llama.tenant.leadmanagement.brand.BrandContactRepository;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms.MailableFilm;
import com.dalai.llama.tenant.showcase.service.FollowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/** Phase D, run daily before the digest (which then delivers what this queues):
 * <ul>
 *   <li>automatic picks: each brand that asked for picks, with an industry, and due by their
 *   cadence, gets one film from their industry they haven't seen (spotlighted films first), from a
 *   creator who allows picks and hasn't mailed them within the cooldown;</li>
 *   <li>follower notices: films that became mailable since the last run go to the creator's
 *   followers.</li>
 * </ul>
 * Neither touches the creator's mail allowance; both go only through the daily digest. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoPickJob {

    private static final int CANDIDATES = 50;

    private final BrandContactRepository contacts;
    private final MailableFilms mailableFilms;
    private final OutreachService outreach;
    private final OutreachStore store;
    private final RecipientHasher hasher;
    private final FollowService follows;
    private final Clock clock;

    public record RunSummary(int picksQueued, int followerNoticesQueued) {
    }

    @Scheduled(cron = "${outreach.auto-pick-cron:0 30 12 * * *}", zone = "UTC")
    public void scheduled() {
        RunSummary summary = run(clock.instant().minus(Duration.ofDays(1)));
        log.info("Automatic picks: {} picks, {} follower notices queued", summary.picksQueued(), summary.followerNoticesQueued());
    }

    /** {@code newFilmsSince}: films published after this get follower notices. */
    public RunSummary run(Instant newFilmsSince) {
        return new RunSummary(queuePicks(), queueFollowerNotices(newFilmsSince));
    }

    private int queuePicks() {
        Instant now = clock.instant();
        int queued = 0;
        for (BrandContact brand : contacts.findByAutoPicksOptInTrue()) {
            if (brand.getIndustry() == null || !due(brand, now)) continue;
            Set<java.util.UUID> seen = store.itemsFor(hasher.hash(brand.getEmail()));
            for (MailableFilm film : mailableFilms.forAutoPicks(brand.getIndustry(), now, CANDIDATES)) {
                if (seen.contains(film.itemId())) continue;
                if (outreach.enqueue(film.tenantId(), brand.getEmail(), brand.getContactName(), OutreachTemplate.SIMILAR_BRAND_WORK,
                        OutreachStore.Origin.AUTO_PICK, film.itemId())) {
                    brand.setLastAutoPickAt(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
                    contacts.save(brand);
                    queued++;
                    break;
                }
            }
        }
        return queued;
    }

    private int queueFollowerNotices(Instant since) {
        int queued = 0;
        for (MailableFilm film : mailableFilms.publishedSince(since)) {
            for (java.util.UUID followerId : follows.followers(film.tenantId())) {
                BrandContact brand = contacts.findById(followerId).orElse(null);
                if (brand != null && outreach.enqueue(film.tenantId(), brand.getEmail(), brand.getContactName(),
                        OutreachTemplate.NEW_FILM, OutreachStore.Origin.FOLLOW, film.itemId())) {
                    queued++;
                }
            }
        }
        return queued;
    }

    private static boolean due(BrandContact brand, Instant now) {
        return brand.getLastAutoPickAt() == null
                || brand.getLastAutoPickAt().toInstant().plus(Duration.ofDays(brand.getAutoCadenceDays())).isBefore(now);
    }
}
