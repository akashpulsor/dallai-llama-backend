package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.leadmanagement.audience.AudienceStore;
import com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateStore.EmailTemplate;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachAnalyticsStore.Counts;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachAnalyticsStore.GroupBy;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rule 28: what the Marketing tab shows: totals, every template the creator can use (or used),
 * and every audience, each with sent / queued / clicks / requests. */
@Service
@RequiredArgsConstructor
public class OutreachAnalyticsService {

    public record Totals(int delivered, int queued, int clicks, int requestsFromMail, int followers) {
    }

    public record TemplateStat(UUID templateId, String name, OutreachTemplate layout, boolean global, boolean active,
                               int sent, int queued, int clicks, int requests) {
    }

    public record AudienceStat(UUID audienceId, String name, int leads, int reachable, int sent, int queued, int clicks,
                               int requests) {
    }

    public record AnalyticsView(int days, Totals totals, List<TemplateStat> templates, List<AudienceStat> audiences) {
    }

    private final OutreachAnalyticsStore analytics;
    private final OutreachStore outreach;
    private final EmailTemplateStore templates;
    private final AudienceStore audiences;
    private final CreatorPublicProfileRepository profiles;
    private final Clock clock;

    public AnalyticsView view(UUID tenantId, int days) {
        int window = Math.max(1, Math.min(days, 90));
        Instant since = clock.instant().minus(Duration.ofDays(window));
        OutreachStore.Reach reach = outreach.reach(tenantId, since);
        int followers = profiles.findById(tenantId).map(p -> p.getFollowerCount()).orElse(0);
        Totals totals = new Totals(reach.mailsDelivered(), analytics.queued(tenantId, since), reach.clicks(),
                reach.requestsFromMail(), followers);

        Map<UUID, Counts> byTemplate = analytics.by(GroupBy.TEMPLATE, tenantId, since);
        Map<UUID, EmailTemplate> shown = new LinkedHashMap<>();
        templates.usableBy(tenantId).forEach(t -> shown.put(t.id(), t));
        // A template deactivated since still shows if it was used in the window.
        byTemplate.keySet().stream().filter(id -> !shown.containsKey(id))
                .forEach(id -> templates.find(id).ifPresent(t -> shown.put(id, t)));
        List<TemplateStat> templateStats = new ArrayList<>();
        shown.values().forEach(t -> {
            Counts c = byTemplate.getOrDefault(t.id(), Counts.NONE);
            templateStats.add(new TemplateStat(t.id(), t.name(), t.layout(), t.global(), t.active(), c.sent(), c.queued(),
                    c.clicks(), c.requests()));
        });

        Map<UUID, Counts> byAudience = analytics.by(GroupBy.AUDIENCE, tenantId, since);
        List<AudienceStat> audienceStats = audiences.audiences(tenantId).stream().map(a -> {
            Counts c = byAudience.getOrDefault(a.id(), Counts.NONE);
            return new AudienceStat(a.id(), a.name(), a.leads(), a.reachable(), c.sent(), c.queued(), c.clicks(), c.requests());
        }).toList();
        return new AnalyticsView(window, totals, templateStats, audienceStats);
    }
}
