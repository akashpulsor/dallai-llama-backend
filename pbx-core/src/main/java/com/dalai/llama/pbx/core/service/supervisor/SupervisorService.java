package com.dalai.llama.pbx.core.service.supervisor;


import com.dalai.llama.pbx.core.domain.enums.AgentStatus;
import com.dalai.llama.pbx.core.esl.EslCommandExecutor;
import com.dalai.llama.pbx.core.redis.ActiveCallTracker;
import com.dalai.llama.pbx.core.redis.ChannelCounterService;
import com.dalai.llama.pbx.core.repository.cdr.CallRecordRepository;
import com.dalai.llama.pbx.core.repository.core.AgentRepository;
import com.dalai.llama.pbx.core.repository.core.QueueMemberRepository;
import com.dalai.llama.pbx.core.service.call.CallControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Supervisor operations — live call monitoring + dashboard metrics.
 *
 * Eavesdrop modes (all use FreeSWITCH eavesdrop application via ESL):
 *   - LISTEN: supervisor hears both legs, neither party hears supervisor
 *   - WHISPER: supervisor can speak to agent, caller doesn't hear
 *   - BARGE: all three parties hear each other (3-way conference)
 *
 * Dashboard metrics come from:
 *   - ActiveCallTracker (Redis) → live call count, call details
 *   - ChannelCounterService (Redis) → inbound/outbound channel counts
 *   - AgentRepository (DB) → agents by status
 *   - CallRecordRepository (DB) → today's call stats
 *   - QueueMemberRepository (DB) → available agents per queue
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupervisorService {

    private final EslCommandExecutor esl;
    private final CallControlService callControlService;
    private final ActiveCallTracker callTracker;
    private final ChannelCounterService channelCounter;
    private final AgentRepository agentRepository;
    private final CallRecordRepository callRecordRepository;
    private final QueueMemberRepository queueMemberRepository;

    // ═══════════════════════════════════════════════════════════
    // EAVESDROP MODES
    // ═══════════════════════════════════════════════════════════

    /**
     * Listen mode — supervisor hears both legs silently.
     * Supervisor must already have an active call leg (originated to them).
     */
    public String listen(String supervisorUuid, String targetCallId) {
        log.info("Supervisor LISTEN: supervisor={} target={}", supervisorUuid, targetCallId);
        return esl.listen(supervisorUuid, targetCallId);
    }

    /**
     * Whisper mode — supervisor speaks to agent only.
     * Sets eavesdrop_whisper_aleg flag on the target call.
     */
    public String whisper(String supervisorUuid, String targetCallId) {
        log.info("Supervisor WHISPER: supervisor={} target={}", supervisorUuid, targetCallId);
        return esl.whisper(supervisorUuid, targetCallId);
    }

    /**
     * Barge mode — supervisor joins as 3rd party, all hear each other.
     */
    public String barge(String supervisorUuid, String targetCallId) {
        log.info("Supervisor BARGE: supervisor={} target={}", supervisorUuid, targetCallId);
        return esl.barge(supervisorUuid, targetCallId);
    }

    /**
     * Find which call an agent is currently on.
     * Supervisor UI calls this to get the targetCallId for listen/whisper/barge.
     */
    public Optional<String> findAgentActiveCall(UUID tenantId, UUID agentId) {
        return callTracker.findCallByAgent(tenantId, agentId.toString());
    }

    // ═══════════════════════════════════════════════════════════
    // DASHBOARD — Live metrics
    // ═══════════════════════════════════════════════════════════

    /**
     * Complete supervisor dashboard snapshot.
     * Returns a single JSON-friendly map with all live metrics.
     */
    public Map<String, Object> getDashboard(UUID tenantId) {
        Instant todayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant now = Instant.now();

        Map<String, Object> dashboard = new LinkedHashMap<>();

        // ── Live call stats (Redis) ──
        dashboard.put("activeCalls", callTracker.getActiveCallCount(tenantId));
        dashboard.put("channelsInbound", channelCounter.getInbound(tenantId));
        dashboard.put("channelsOutbound", channelCounter.getOutbound(tenantId));
        dashboard.put("channelsTotal", channelCounter.getTotal(tenantId));

        // ── Agent stats (DB) ──
        dashboard.put("agentsOnline", agentRepository.countByTenantIdAndStatus(tenantId, AgentStatus.ONLINE));
        dashboard.put("agentsOnCall", agentRepository.countByTenantIdAndStatus(tenantId, AgentStatus.ON_CALL));
        dashboard.put("agentsBusy", agentRepository.countByTenantIdAndStatus(tenantId, AgentStatus.BUSY));
        dashboard.put("agentsBreak", agentRepository.countByTenantIdAndStatus(tenantId, AgentStatus.BREAK));
        dashboard.put("agentsWrapUp", agentRepository.countByTenantIdAndStatus(tenantId, AgentStatus.WRAP_UP));
        dashboard.put("agentsOffline", agentRepository.countByTenantIdAndStatus(tenantId, AgentStatus.OFFLINE));
        dashboard.put("agentsTotal", agentRepository.countByTenantIdAndIsActiveTrue(tenantId));

        // ── Today's call stats (DB) ──
        dashboard.put("callsToday", callRecordRepository.countByTenantIdAndCreatedAtBetween(tenantId, todayStart, now));
        dashboard.put("talkSecondsToday", callRecordRepository.sumDurationByTenantIdAndPeriod(tenantId, todayStart, now));
        dashboard.put("avgDurationToday", callRecordRepository.avgDurationByTenantIdAndPeriod(tenantId, todayStart, now));

        // ── Active call details (Redis) ──
        dashboard.put("activeCallDetails", callTracker.getActiveCallsWithDetails(tenantId));

        return dashboard;
    }

    /**
     * Queue-level stats for supervisor — available agents per queue.
     */
    public Map<String, Object> getQueueStats(UUID queueId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalMembers", queueMemberRepository.countByQueueId(queueId));
        stats.put("availableMembers", queueMemberRepository.findAvailableMembersByQueueId(queueId).size());
        return stats;
    }

    // ═══════════════════════════════════════════════════════════
    // AI INSIGHTS — useGetAIInsightsQuery
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> getAiInsights(UUID tenantId, int days) {
        Instant now = Instant.now();
        Instant from = now.minus(days, ChronoUnit.DAYS);

        Map<String, Object> insights = new LinkedHashMap<>();

        // AI minutes consumed
        java.math.BigDecimal aiMinutes = callRecordRepository.sumAiMinutesByPeriodDecimal(tenantId, from, now);
        insights.put("ai_minutes_consumed", aiMinutes);

        // Sentiment analytics
        double avgSentiment = callRecordRepository.avgSentimentByTenantIdAndPeriod(tenantId, from, now);
        long sentimentCallCount = callRecordRepository
                .countByTenantIdAndSentimentScoreIsNotNullAndCreatedAtBetween(tenantId, from, now);
        insights.put("avg_sentiment_score", avgSentiment);
        insights.put("calls_with_sentiment", sentimentCallCount);

        // Flagged calls count (sentiment < -0.3)
        java.math.BigDecimal threshold = java.math.BigDecimal.valueOf(-0.3);
        int flaggedCount = callRecordRepository.findFlaggedCalls(tenantId, threshold, from, now).size();
        insights.put("flagged_calls_count", flaggedCount);

        // Call volume
        long totalCalls = callRecordRepository.countByTenantIdAndCreatedAtBetween(tenantId, from, now);
        insights.put("total_calls", totalCalls);

        // Average duration
        double avgDuration = callRecordRepository.avgDurationByTenantIdAndPeriod(tenantId, from, now);
        insights.put("avg_duration_seconds", avgDuration);

        // AI utilization rate (calls with AI / total calls)
        if (totalCalls > 0) {
            insights.put("ai_utilization_rate",
                    java.math.BigDecimal.valueOf(sentimentCallCount)
                            .divide(java.math.BigDecimal.valueOf(totalCalls), 4, java.math.RoundingMode.HALF_UP));
        } else {
            insights.put("ai_utilization_rate", java.math.BigDecimal.ZERO);
        }

        insights.put("period_days", days);
        return insights;
    }

    // ═══════════════════════════════════════════════════════════
    // AGENT LEADERBOARD — useGetAgentLeaderboardQuery
    // ═══════════════════════════════════════════════════════════

    public List<Map<String, Object>> getAgentLeaderboard(UUID tenantId, int days) {
        Instant now = Instant.now();
        Instant from = now.minus(days, ChronoUnit.DAYS);

        List<Object[]> rows = callRecordRepository.agentLeaderboard(tenantId, from, now);
        List<Map<String, Object>> leaderboard = new ArrayList<>();

        int rank = 1;
        for (Object[] row : rows) {
            UUID agentId = (UUID) row[0];
            long callCount = (Long) row[1];
            long totalDuration = (Long) row[2];
            double avgDuration = (Double) row[3];

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", rank++);
            entry.put("agent_id", agentId);
            entry.put("call_count", callCount);
            entry.put("total_duration_seconds", totalDuration);
            entry.put("avg_duration_seconds", avgDuration);

            // Enrich with agent name/extension
            agentRepository.findById(agentId).ifPresent(agent -> {
                entry.put("agent_name", agent.getDisplayName() != null ? agent.getDisplayName() : agent.getUsername());
                entry.put("extension", agent.getExtension());
                entry.put("status", agent.getStatus() != null ? agent.getStatus().name() : "UNKNOWN");
            });

            leaderboard.add(entry);
        }

        return leaderboard;
    }

    // ═══════════════════════════════════════════════════════════
    // TEAM METRICS — useGetTeamMetricsQuery
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> getTeamMetrics(UUID tenantId) {
        Instant todayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant now = Instant.now();
        Instant weekStart = now.minus(7, ChronoUnit.DAYS);

        Map<String, Object> metrics = new LinkedHashMap<>();

        // ── Agent status distribution ──
        Map<String, Long> statusDist = new LinkedHashMap<>();
        for (AgentStatus status : AgentStatus.values()) {
            statusDist.put(status.name(), agentRepository.countByTenantIdAndStatus(tenantId, status));
        }
        metrics.put("agent_status_distribution", statusDist);
        metrics.put("total_agents", agentRepository.countByTenantIdAndIsActiveTrue(tenantId));

        // ── Today's metrics ──
        long callsToday = callRecordRepository.countByTenantIdAndCreatedAtBetween(tenantId, todayStart, now);
        long talkSecondsToday = callRecordRepository.sumDurationByTenantIdAndPeriod(tenantId, todayStart, now);
        double avgDurationToday = callRecordRepository.avgDurationByTenantIdAndPeriod(tenantId, todayStart, now);
        metrics.put("calls_today", callsToday);
        metrics.put("talk_seconds_today", talkSecondsToday);
        metrics.put("avg_duration_today", avgDurationToday);

        // ── This week's metrics ──
        long callsThisWeek = callRecordRepository.countByTenantIdAndCreatedAtBetween(tenantId, weekStart, now);
        long talkSecondsWeek = callRecordRepository.sumDurationByTenantIdAndPeriod(tenantId, weekStart, now);
        double avgDurationWeek = callRecordRepository.avgDurationByTenantIdAndPeriod(tenantId, weekStart, now);
        metrics.put("calls_this_week", callsThisWeek);
        metrics.put("talk_seconds_this_week", talkSecondsWeek);
        metrics.put("avg_duration_this_week", avgDurationWeek);

        // ── Direction breakdown (today) ──
        long inboundToday = callRecordRepository.countByTenantIdAndDirectionAndCreatedAtBetween(
                tenantId, com.dalai.llama.pbx.core.domain.enums.CallDirection.INBOUND, todayStart, now);
        long outboundToday = callRecordRepository.countByTenantIdAndDirectionAndCreatedAtBetween(
                tenantId, com.dalai.llama.pbx.core.domain.enums.CallDirection.OUTBOUND, todayStart, now);
        metrics.put("inbound_calls_today", inboundToday);
        metrics.put("outbound_calls_today", outboundToday);

        // ── Live channels ──
        metrics.put("active_calls", callTracker.getActiveCallCount(tenantId));
        metrics.put("channels_inbound", channelCounter.getInbound(tenantId));
        metrics.put("channels_outbound", channelCounter.getOutbound(tenantId));

        return metrics;
    }

    // ═══════════════════════════════════════════════════════════
    // FLAGGED CALLS — useGetFlaggedCallsQuery
    // ═══════════════════════════════════════════════════════════

    public List<Map<String, Object>> getFlaggedCalls(UUID tenantId, int days, double sentimentThreshold) {
        Instant now = Instant.now();
        Instant from = now.minus(days, ChronoUnit.DAYS);
        java.math.BigDecimal threshold = java.math.BigDecimal.valueOf(sentimentThreshold);

        var flagged = callRecordRepository.findFlaggedCalls(tenantId, threshold, from, now);
        List<Map<String, Object>> result = new ArrayList<>();

        for (var cr : flagged) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("call_id", cr.getCallId());
            entry.put("id", cr.getId());
            entry.put("direction", cr.getDirection() != null ? cr.getDirection().name() : null);
            entry.put("caller_number", cr.getCallerNumber());
            entry.put("callee_number", cr.getCalleeNumber());
            entry.put("agent_id", cr.getAgentId());
            entry.put("sentiment_score", cr.getSentimentScore());
            entry.put("transcript_summary", cr.getTranscriptSummary());
            entry.put("duration_seconds", cr.getDurationSeconds());
            entry.put("hangup_cause", cr.getHangupCause());
            entry.put("recording_url", cr.getRecordingUrl());
            entry.put("created_at", cr.getCreatedAt());

            // Enrich with agent name
            if (cr.getAgentId() != null) {
                agentRepository.findById(cr.getAgentId()).ifPresent(agent -> {
                    entry.put("agent_name", agent.getDisplayName() != null ? agent.getDisplayName() : agent.getUsername());
                });
            }

            result.add(entry);
        }

        return result;
    }
}