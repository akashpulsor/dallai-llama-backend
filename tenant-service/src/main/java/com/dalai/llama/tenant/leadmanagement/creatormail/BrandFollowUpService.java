package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.leadmanagement.creatormail.BrandFollowUpStore.MailedBrand;
import com.dalai.llama.tenant.leadmanagement.creatormail.BrandFollowUpStore.Stage;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Audience;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.MailFor;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Recipient;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Template;
import com.dalai.llama.tenant.leadmanagement.outreach.MailThemes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The weekly inspiration campaign: brands that were mailed and haven't sent a request get video ideas a
 * week after their last mail, from the templates picked for their stage (not opened, opened, clicked), in
 * turn. Each mail must bring a video the brand hasn't been sent: a brand that has seen them all waits for
 * new ones. After {@code brand-follow-up.max} mails with no request, even if it opened or clicked, a brand
 * gets one last mail asking what it would like; if it answers in its console, the weekly mail carries on with
 * what it asked for. It only runs while ops have it switched on. The mails join the ordinary queue, so its
 * daily cap and unsubscribe check apply. A plain letter can't report an open, so someone who read one without
 * clicking counts as not opened.
 */
@Slf4j
@Service
public class BrandFollowUpService {

    private static final Duration WAIT = Duration.ofDays(7);
    private static final List<Stage> FUNNEL = List.of(Stage.NOT_OPENED, Stage.OPENED, Stage.CLICKED);
    private static final List<Stage> LAST_MAILS = List.of(Stage.LAST_NOT_OPENED, Stage.LAST_OPENED);

    public record StageView(Stage stage, int brands, int due, List<UUID> templateIds) {
    }

    /** {@code requested}, {@code unsubscribed}, {@code badAddress}, {@code finished}: brands out of the funnel, each counted once.
     * {@code finished}: had every weekly mail with no request; {@code lastMailDue} of them are due their last mail. */
    public record FollowUpView(boolean enabled, int maxFollowUps, List<StageView> stages, List<StageView> lastMails, int requested,
                               int unsubscribed, int badAddress, int finished) {
    }

    public record StageTemplates(Stage stage, List<UUID> templateIds) {
    }

    public record FollowUpSettings(boolean enabled, List<StageTemplates> stages) {
    }

    private final BrandFollowUpStore store;
    private final CreatorMailStore mail;
    private final CreatorMailService mails;
    private final Clock clock;
    private final int maxFollowUps;

    public BrandFollowUpService(BrandFollowUpStore store, CreatorMailStore mail, CreatorMailService mails, Clock clock,
                                @Value("${brand-follow-up.max:4}") int maxFollowUps) {
        this.store = store;
        this.mail = mail;
        this.mails = mails;
        this.clock = clock;
        this.maxFollowUps = maxFollowUps;
    }

    @Scheduled(cron = "${brand-follow-up.cron:0 30 4 * * *}", zone = "UTC")
    public void scheduled() {
        int queued = run();
        if (queued > 0) log.info("Brand follow-ups: {} queued", queued);
    }

    /** Queues a follow-up for each brand that's due; how many. Nothing while switched off. */
    @Transactional
    public int run() {
        if (!store.enabled()) return 0;
        Map<Stage, List<Template>> templates = new HashMap<>();
        Map<UUID, UUID> sends = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        Instant now = clock.instant();
        for (MailedBrand brand : store.mailedBrands()) {
            boolean last = isLastMailDue(brand, now);
            if (!last && !isDue(brand, now)) continue;
            List<Template> options = templates.computeIfAbsent(last ? brand.lastMail() : brand.stage(),
                    s -> store.templates(s, true).stream().map(id -> mail.find(id).orElseThrow()).toList());
            if (options.isEmpty()) continue;
            Template template = options.get(brand.followUps() % options.size());
            Recipient to = recipient(brand);
            if (!last && !mails.hasNewIdeas(template, to)) continue;
            UUID sendId = sends.computeIfAbsent(template.id(), id -> mail.logSend(id, template.subject(), Audience.FOLLOWUP, 0));
            mail.enqueueWeekly(sendId, template.id(), to);
            counts.merge(sendId, 1, Integer::sum);
        }
        counts.forEach(mail::setRecipients);
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public FollowUpView view() {
        List<MailedBrand> brands = store.mailedBrands();
        Instant now = clock.instant();
        List<MailedBrand> active = brands.stream().filter(b -> !stopped(b)).toList();
        List<StageView> stages = FUNNEL.stream().map(stage -> {
            List<MailedBrand> inStage = active.stream().filter(b -> b.stage() == stage).toList();
            return new StageView(stage, inStage.size(), (int) inStage.stream().filter(b -> isDue(b, now)).count(), store.templates(stage, false));
        }).toList();
        List<StageView> lastMails = LAST_MAILS.stream().map(stage -> {
            List<MailedBrand> due = brands.stream().filter(b -> b.lastMail() == stage && isLastMailDue(b, now)).toList();
            return new StageView(stage, due.size(), due.size(), store.templates(stage, false));
        }).toList();
        int unsubscribed = (int) brands.stream().filter(MailedBrand::unsubscribed).count();
        int badAddress = (int) brands.stream().filter(b -> !b.unsubscribed() && b.badAddress()).count();
        int requested = (int) brands.stream().filter(b -> !b.unsubscribed() && !b.badAddress() && b.requested()).count();
        int finished = brands.size() - active.size() - unsubscribed - badAddress - requested;
        return new FollowUpView(store.enabled(), maxFollowUps, stages, lastMails, requested, unsubscribed, badAddress, finished);
    }

    /** Turning it off also takes back follow-ups still in the queue, so it stops at once. */
    @Transactional
    public FollowUpView save(FollowUpSettings settings) {
        if (settings.stages() != null) {
            for (StageTemplates stage : settings.stages()) {
                List<UUID> ids = stage.templateIds() == null ? List.of() : stage.templateIds().stream().distinct().toList();
                ids.forEach(id -> requireTemplate(id, stage.stage()));
                store.replaceTemplates(stage.stage(), ids);
            }
        }
        store.setEnabled(settings.enabled());
        if (!settings.enabled()) store.cancelQueued();
        return view();
    }

    /** Out for good: said no, can't be reached, or already responded with a request. */
    private static boolean gone(MailedBrand b) {
        return b.unsubscribed() || b.badAddress() || b.requested();
    }

    /** A brand that told us what it wants keeps getting it; the rest stop at the limit. */
    private boolean stopped(MailedBrand b) {
        return gone(b) || (b.followUps() >= maxFollowUps && !b.gavePriorities());
    }

    private static boolean waited(MailedBrand b, Instant now) {
        return !b.queued() && !b.lastSentAt().toInstant().isAfter(now.minus(WAIT));
    }

    private boolean isDue(MailedBrand b, Instant now) {
        return !stopped(b) && waited(b, now);
    }

    private boolean isLastMailDue(MailedBrand b, Instant now) {
        return !gone(b) && !b.gavePriorities() && b.followUps() == maxFollowUps && waited(b, now);
    }

    private void requireTemplate(UUID templateId, Stage stage) {
        Template template = mail.find(templateId).orElseThrow(() -> new IllegalArgumentException("That template doesn't exist"));
        if (template.mailFor() != MailFor.BRANDS) throw new IllegalArgumentException("A weekly mail has to be a template written for brands");
        if (FUNNEL.contains(stage) && template.ideaVideos() == 0) throw new IllegalArgumentException("A weekly mail has to show videos: give the template some first");
    }

    /** A brand's videos follow what it asked for, when it has said; otherwise what it's known to sell. */
    private static Recipient recipient(MailedBrand b) {
        String topics = b.gavePriorities() && !b.priorityThemes().isBlank() ? MailThemes.topicsFor(List.of(b.priorityThemes().split(","))) : b.topics();
        return new Recipient(b.email(), b.name(), b.recipientHash(), b.company(), b.industry(), topics);
    }
}
