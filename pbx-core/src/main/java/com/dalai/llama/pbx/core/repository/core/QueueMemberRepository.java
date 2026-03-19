package com.dalai.llama.pbx.core.repository.core;


import com.dalai.llama.pbx.core.domain.entity.core.QueueMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Queue members — agent ↔ queue mapping with priority and penalty.
 *
 * Read paths:
 *   - QueueService → pick next agent for incoming call to queue
 *     (ordered by priority ASC, then penalty ASC — lowest values served first)
 *   - AgentController → show which queues an agent belongs to
 *
 * Write paths:
 *   - QueueController → add/remove members
 *   - AgentService → cascade-remove memberships when agent is deleted
 */
@Repository
public interface QueueMemberRepository extends JpaRepository<QueueMember, UUID> {

    /**
     * Members of a queue, ordered by priority ASC (highest priority = lowest number).
     * QueueService iterates this list to find the first available agent.
     */
    List<QueueMember> findByQueueIdOrderByPriorityAscPenaltyAsc(UUID queueId);

    /**
     * All queues an agent belongs to — used by AgentController GET /api/v1/agents/{id}.
     */
    List<QueueMember> findByAgentId(UUID agentId);

    boolean existsByQueueIdAndAgentId(UUID queueId, UUID agentId);

    long countByQueueId(UUID queueId);

    @Modifying
    void deleteByQueueIdAndAgentId(UUID queueId, UUID agentId);

    /**
     * Remove agent from all queues — called by AgentService on agent delete.
     */
    @Modifying
    void deleteByAgentId(UUID agentId);

    /**
     * Available members: agents who are ONLINE in the queue.
     * Used by QueueService to calculate queue stats (agents available).
     */
    @Query("SELECT qm FROM QueueMember qm JOIN qm.agent a " +
            "WHERE qm.queue.id = :queueId AND a.status = 'ONLINE' AND a.isActive = true " +
            "ORDER BY qm.priority ASC, qm.penalty ASC")
    List<QueueMember> findAvailableMembersByQueueId(UUID queueId);
}