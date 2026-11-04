package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryStore.BrandMailSummary;
import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryStore.BrandRequest;
import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryStore.MailLink;
import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryStore.SentMail;
import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryStore.ShownVideo;
import com.dalai.llama.tenant.leadmanagement.creatormail.OpsMailHistoryStore.TemplateStats;
import com.dalai.llama.tenant.leadmanagement.notify.MailRenderer;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.leadmanagement.outreach.MailThemes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Ops' view of brand mail: what each brand was sent and did, how each template does, which kinds of
 * brand the inspiration board has too few videos for, and a daily mail to ops naming the kinds that
 * went out without a fitting video (so videos can be added for them). */
@Slf4j
@Service
public class OpsMailHistoryService {

    /** One mail as the history shows it: the mail, the videos it showed, its links and their clicks. */
    public record MailEntry(SentMail mail, List<ShownVideo> videos, List<MailLink> links) {
    }

    public record BrandHistory(String email, List<MailEntry> mails, List<BrandRequest> requests) {
    }

    /** A kind of product (MailThemes): how many brands in the directory sell it, how many videos fit it. */
    public record VideoCoverage(String theme, int brands, int videos) {
    }

    /** A kind of product the day's brand mail had no fitting video for, and the brands it went to. */
    public record Shortfall(String theme, List<String> brands) {
    }

    private final OpsMailHistoryStore store;
    private final PlatformMailer mailer;
    private final Clock clock;
    private final String alertEmail;

    public OpsMailHistoryService(OpsMailHistoryStore store, PlatformMailer mailer, Clock clock,
                                 @Value("${creator-mail.alert-email:akashtripathi.2801@gmail.com}") String alertEmail) {
        this.store = store;
        this.mailer = mailer;
        this.clock = clock;
        this.alertEmail = alertEmail;
    }

    public List<BrandMailSummary> brands(String query) {
        return store.brands(query, 100);
    }

    public BrandHistory brand(String email) {
        String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        List<MailEntry> mails = store.mailsTo(address).stream()
                .map(m -> new MailEntry(m, store.videosOf(m.deliveryId()), store.linksOf(m.deliveryId()))).toList();
        return new BrandHistory(address, mails, store.requestsBy(address));
    }

    public List<TemplateStats> templateStats() {
        return store.templateStats();
    }

    /** Every kind of product a brand in the directory sells, the ones with fewest videos per brand first. */
    public List<VideoCoverage> coverage() {
        Map<String, int[]> counts = new TreeMap<>();
        for (String topics : store.brandTopics()) {
            for (String theme : MailThemes.of(topics)) counts.computeIfAbsent(theme, t -> new int[2])[0]++;
        }
        for (String title : store.mailableVideoTitles()) {
            for (String theme : MailThemes.of(title)) counts.computeIfAbsent(theme, t -> new int[2])[1]++;
        }
        return counts.entrySet().stream().filter(e -> e.getValue()[0] > 0)
                .map(e -> new VideoCoverage(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingDouble((VideoCoverage c) -> (double) c.videos() / c.brands()).thenComparing(VideoCoverage::theme))
                .toList();
    }

    /** The kinds of product the last day's brand mail went out without a fitting video for. */
    public List<Shortfall> shortfalls() {
        Map<String, TreeSet<String>> byTheme = new TreeMap<>();
        for (OpsMailHistoryStore.Shortfall s : store.shortfallsSince(clock.instant().minus(Duration.ofDays(1)))) {
            for (String theme : MailThemes.of(s.topics())) {
                byTheme.computeIfAbsent(theme, t -> new TreeSet<>()).add(s.company() == null ? "(no name)" : s.company());
            }
        }
        return byTheme.entrySet().stream().map(e -> new Shortfall(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    /** Once a day: when brand mail went out without a fitting video, ops get one mail saying for what. */
    @Scheduled(cron = "${creator-mail.shortfall-cron:0 30 3 * * *}", zone = "UTC")
    public void mailShortfalls() {
        List<Shortfall> shortfalls = shortfalls();
        if (shortfalls.isEmpty()) return;
        StringBuilder text = new StringBuilder("Brand mail in the last day went to these brands with no video about what they sell.\n"
                + "Add inspiration videos for these kinds and the next mails will carry them.\n");
        StringBuilder html = new StringBuilder("<p>Brand mail in the last day went to these brands with no video about what they sell. "
                + "Add inspiration videos for these kinds and the next mails will carry them.</p><ul>");
        for (Shortfall s : shortfalls) {
            String line = s.theme() + ": " + s.brands().size() + (s.brands().size() == 1 ? " brand" : " brands") + " (" + String.join(", ", s.brands()) + ")";
            text.append("\n- ").append(line);
            html.append("<li>").append(MailRenderer.escape(line)).append("</li>");
        }
        html.append("</ul>");
        String subject = "Brand mail short of videos: " + String.join(", ", shortfalls.stream().map(Shortfall::theme).toList());
        if (mailer.send(new PlatformMailer.Mail(alertEmail, subject, text.toString(), html.toString()))) {
            log.info("Mailed ops about {} kinds short of videos", shortfalls.size());
        }
    }
}
