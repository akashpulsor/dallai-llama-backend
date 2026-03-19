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
}