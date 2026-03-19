package com.dalai.llama.pbx.core.repository.cdr;


import com.dalai.llama.pbx.core.domain.entity.cdr.CallRecord;
import com.dalai.llama.pbx.core.domain.enums.CallDirection;
import com.dalai.llama.pbx.core.domain.enums.CallStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Call Detail Records — created on call-start, updated on call-end.
 *
 * Write paths:
 *   - KamailioAuthController /internal/kamailio/events/call-start → INSERT
 *   - KamailioAuthController /internal/kamailio/events/call-end   → UPDATE (duration, cost, hangup)
 *   - EslEventListener → UPDATE (answer_time, recording_url, agent assignment)
 *   - CdrService → billing cost calculation after call-end
 *
 * Read paths:
 *   - SupervisorController → dashboard stats (calls today, avg duration)
 *   - AgentController → agent call history
 *   - CampaignService → outbound campaign stats
 *   - Billing/reporting exports
 */
@Repository
public interface CallRecordRepository extends JpaRepository<CallRecord, UUID> {

    // ── Single record lookup ──

    /**
     * Primary lookup — SIP Call-ID is unique per call.
     * Used by call-end event handler to find and update the CDR.
     */
    Optional<CallRecord> findByCallId(String callId);

    // ── Tenant call history (paginated) ──

    Page<CallRecord> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<CallRecord> findByTenantIdAndDirectionOrderByCreatedAtDesc(
            UUID tenantId, CallDirection direction, Pageable pageable);

    Page<CallRecord> findByTenantIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID tenantId, Instant from, Instant to, Pageable pageable);

    Page<CallRecord> findByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, CallStatus status, Pageable pageable);

    // ── Agent call history ──

    Page<CallRecord> findByAgentIdOrderByCreatedAtDesc(UUID agentId, Pageable pageable);

    Page<CallRecord> findByAgentIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID agentId, Instant from, Instant to, Pageable pageable);

    // ── Queue call history ──

    Page<CallRecord> findByQueueIdOrderByCreatedAtDesc(UUID queueId, Pageable pageable);

    // ── Dashboard counts ──

    long countByTenantIdAndStatus(UUID tenantId, CallStatus status);

    long countByTenantIdAndDirection(UUID tenantId, CallDirection direction);

    long countByTenantIdAndCreatedAtBetween(UUID tenantId, Instant from, Instant to);

    long countByTenantIdAndDirectionAndCreatedAtBetween(
            UUID tenantId, CallDirection direction, Instant from, Instant to);

    // ── Aggregations (supervisor dashboard) ──

    /**
     * Total talk seconds for a tenant in a time period.
     * Used by supervisor dashboard and billing.
     */
    @Query("SELECT COALESCE(SUM(cr.durationSeconds), 0) FROM CallRecord cr " +
            "WHERE cr.tenantId = :tenantId AND cr.createdAt BETWEEN :from AND :to")
    long sumDurationByTenantIdAndPeriod(UUID tenantId, Instant from, Instant to);

    /**
     * Total billable seconds — for billing cost calculation.
     */
    @Query("SELECT COALESCE(SUM(cr.billableSeconds), 0) FROM CallRecord cr " +
            "WHERE cr.tenantId = :tenantId AND cr.createdAt BETWEEN :from AND :to")
    long sumBillableSecondsByTenantIdAndPeriod(UUID tenantId, Instant from, Instant to);

    /**
     * Average call duration — supervisor dashboard metric.
     */
    @Query("SELECT COALESCE(AVG(cr.durationSeconds), 0) FROM CallRecord cr " +
            "WHERE cr.tenantId = :tenantId AND cr.status = 'COMPLETED' " +
            "AND cr.createdAt BETWEEN :from AND :to")
    double avgDurationByTenantIdAndPeriod(UUID tenantId, Instant from, Instant to);

    /**
     * Total AI minutes consumed — for AI billing.
     */
    @Query("SELECT COALESCE(SUM(cr.aiMinutes), 0) FROM CallRecord cr " +
            "WHERE cr.tenantId = :tenantId AND cr.createdAt BETWEEN :from AND :to")
    double sumAiMinutesByTenantIdAndPeriod(UUID tenantId, Instant from, Instant to);

    // ── Stale CDR cleanup ──

    /**
     * Find calls stuck in RINGING for too long (no answer event received).
     * ScheduledTasks closes these as MISSED after 10 minutes.
     */
    @Query("SELECT cr FROM CallRecord cr WHERE cr.status = 'RINGING' " +
            "AND cr.startTime < :cutoff")
    java.util.List<CallRecord> findStaleRingingCalls(Instant cutoff);

    /**
     * Bulk close stale calls — sets status to MISSED and endTime to now.
     */
    @Modifying
    @Query("UPDATE CallRecord cr SET cr.status = 'MISSED', cr.endTime = CURRENT_TIMESTAMP " +
            "WHERE cr.status = 'RINGING' AND cr.startTime < :cutoff")
    int closeStaleRingingCalls(Instant cutoff);
}
