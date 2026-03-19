package com.dalai.llama.pbx.core.repository.core;


import com.dalai.llama.pbx.core.domain.entity.core.Agent;
import com.dalai.llama.pbx.core.domain.enums.AgentRole;
import com.dalai.llama.pbx.core.domain.enums.AgentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent table — contact center agents.
 *
 * Read paths:
 *   - CallAuthorizationService → routing to specific agent
 *   - QueueService → pick available agent from queue members
 *   - SupervisorController → dashboard (agents by status)
 *   - EslEventListener → update status on CHANNEL_ANSWER/HANGUP
 *
 * Write paths:
 *   - AgentController CRUD → also syncs subscriber table via AgentService
 *   - EslEventListener → status transitions (ONLINE→ON_CALL→WRAP_UP→ONLINE)
 *   - ScheduledTasks → auto-logout agents with no SIP registration

 * Agent table — contact center agents.
 *
 * Read paths:
 *   - CallAuthorizationService → routing to specific agent
 *   - QueueService → pick available agent from queue members
 *   - SupervisorController → dashboard (agents by status)
 *   - EslEventListener → update status on CHANNEL_ANSWER/HANGUP
 *
 * Write paths:
 *   - AgentController CRUD → also syncs subscriber table via AgentService
 *   - EslEventListener → status transitions (ONLINE→ON_CALL→WRAP_UP→ONLINE)
 *   - ScheduledTasks → auto-logout agents with no SIP registration
 */
@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {

    // ── Tenant-scoped listing ──

    List<Agent> findByTenantId(UUID tenantId);

    List<Agent> findByTenantIdAndIsActiveTrue(UUID tenantId);

    List<Agent> findByTenantIdAndRole(UUID tenantId, AgentRole role);

    // ── Status queries (supervisor dashboard, queue routing) ──

    List<Agent> findByTenantIdAndStatus(UUID tenantId, AgentStatus status);

    /**
     * Available agents: ONLINE + active + ordered by last status change (longest idle first).
     * Used by QueueService for LONGEST_IDLE strategy.
     */
    @Query("SELECT a FROM Agent a WHERE a.tenantId = :tenantId " +
            "AND a.status = 'ONLINE' AND a.isActive = true " +
            "ORDER BY a.updatedAt ASC")
    List<Agent> findAvailableAgents(UUID tenantId);

    /**
     * Available agents with a specific skill — used by SKILLS_BASED queue strategy.
     * skills is JSONB array, so we use PostgreSQL @> containment operator.
     */
    @Query(value = "SELECT * FROM agents a WHERE a.tenant_id = :tenantId " +
            "AND a.status = 'ONLINE' AND a.is_active = true " +
            "AND a.skills @> CAST(:skill AS jsonb) " +
            "ORDER BY a.updated_at ASC",
            nativeQuery = true)
    List<Agent> findAvailableAgentsWithSkill(UUID tenantId, String skill);

    // ── Lookup by identifiers ──

    Optional<Agent> findByTenantIdAndUsername(UUID tenantId, String username);

    Optional<Agent> findByTenantIdAndExtension(UUID tenantId, String extension);

    Optional<Agent> findByTenantIdAndEmail(UUID tenantId, String email);

    Optional<Agent> findByKeycloakUserId(String keycloakUserId);

    Optional<Agent> findByTenantIdAndKeycloakUserId(UUID tenantId, String keycloakUserId);

    // ── Counts (dashboard stats, entitlement checks) ──

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndIsActiveTrue(UUID tenantId);

    long countByTenantIdAndStatus(UUID tenantId, AgentStatus status);

    // ── Bulk operations ──

    /**
     * Update agent status — called by EslEventListener on call events
     * and by AgentController PUT /api/v1/agents/{id}/status.
     */
    @Modifying
    @Query("UPDATE Agent a SET a.status = :status, a.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE a.id = :agentId")
    int updateStatus(UUID agentId, AgentStatus status);

    /**
     * Auto-logout: set all ONLINE agents to OFFLINE for a tenant.
     * Used by ScheduledTasks when detecting stale registrations.
     */
    @Modifying
    @Query("UPDATE Agent a SET a.status = 'OFFLINE', a.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE a.tenantId = :tenantId AND a.status IN ('ONLINE', 'BREAK')")
    int logoutAllAgents(UUID tenantId);
}