package com.dalai.llama.pbx.core.service.queue;


import com.dalai.llama.pbx.core.domain.entity.core.Agent;
import com.dalai.llama.pbx.core.domain.entity.core.Queue;
import com.dalai.llama.pbx.core.domain.entity.core.QueueMember;
import com.dalai.llama.pbx.core.domain.enums.QueueStrategy;
import com.dalai.llama.pbx.core.repository.core.AgentRepository;
import com.dalai.llama.pbx.core.repository.core.QueueMemberRepository;
import com.dalai.llama.pbx.core.repository.core.QueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;


/**
 * Queue management — CRUD + call distribution logic.
 *
 * When a call is routed to a queue (routing target = QUEUE:{queueId}),
 * FreeSWITCH dialplan calls back to PBX-Core to pick the next agent.
 * The picking strategy is configured per queue:
 *
 *   ROUND_ROBIN  — rotate through members in priority order
 *   LONGEST_IDLE — pick the agent who's been ONLINE longest without a call
 *   SKILLS_BASED — match required skills from routing policy metadata
 *   RING_ALL     — ring all available members simultaneously
 *   AI_ROUTED    — future: AI selects best agent based on context
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueRepository queueRepository;
    private final QueueMemberRepository memberRepository;
    private final AgentRepository agentRepository;

    // ═══════════════════════════════════════════════════════════
    // QUEUE CRUD
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public Queue createQueue(UUID tenantId, UUID subscriptionId, String name,
                             QueueStrategy strategy, Integer maxWaitSeconds,
                             String mohFile, Integer wrapUpSeconds) {
        if (queueRepository.existsByTenantIdAndName(tenantId, name)) {
            throw new IllegalArgumentException("Queue '" + name + "' already exists");
        }

        Queue queue = Queue.builder()
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .name(name)
                .strategy(strategy != null ? strategy : QueueStrategy.ROUND_ROBIN)
                .maxWaitSeconds(maxWaitSeconds != null ? maxWaitSeconds : 300)
                .mohFile(mohFile)
                .wrapUpSeconds(wrapUpSeconds != null ? wrapUpSeconds : 15)
                .build();

        queue = queueRepository.save(queue);
        log.info("Created queue '{}' for tenant {} (strategy={})", name, tenantId, queue.getStrategy());
        return queue;
    }

    @Transactional
    public Queue updateQueue(UUID queueId, String name, QueueStrategy strategy,
                             Integer maxWaitSeconds, String mohFile, Integer wrapUpSeconds) {
        Queue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Queue not found: " + queueId));

        if (name != null) queue.setName(name);
        if (strategy != null) queue.setStrategy(strategy);
        if (maxWaitSeconds != null) queue.setMaxWaitSeconds(maxWaitSeconds);
        if (mohFile != null) queue.setMohFile(mohFile);
        if (wrapUpSeconds != null) queue.setWrapUpSeconds(wrapUpSeconds);

        return queueRepository.save(queue);
    }

    @Transactional
    public void deleteQueue(UUID queueId) {
        Queue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Queue not found: " + queueId));
        queue.setIsActive(false);
        queueRepository.save(queue);
        log.info("Deactivated queue {}", queueId);
    }

    public List<Queue> getByTenantId(UUID tenantId) {
        return queueRepository.findByTenantIdAndIsActiveTrue(tenantId);
    }

    public Optional<Queue> getById(UUID queueId) {
        return queueRepository.findById(queueId);
    }

    // ═══════════════════════════════════════════════════════════
    // MEMBER CRUD
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public QueueMember addMember(UUID queueId, UUID agentId, Integer priority, Integer penalty) {
        if (memberRepository.existsByQueueIdAndAgentId(queueId, agentId)) {
            throw new IllegalArgumentException("Agent already in queue");
        }

        Queue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Queue not found: " + queueId));
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        QueueMember member = QueueMember.builder()
                .queue(queue)
                .agent(agent)
                .priority(priority != null ? priority : 1)
                .penalty(penalty != null ? penalty : 0)
                .build();

        member = memberRepository.save(member);
        log.info("Added agent {} to queue {} (priority={}, penalty={})",
                agentId, queueId, member.getPriority(), member.getPenalty());
        return member;
    }

    @Transactional
    public void removeMember(UUID queueId, UUID agentId) {
        memberRepository.deleteByQueueIdAndAgentId(queueId, agentId);
        log.info("Removed agent {} from queue {}", agentId, queueId);
    }

    public List<QueueMember> getMembers(UUID queueId) {
        return memberRepository.findByQueueIdOrderByPriorityAscPenaltyAsc(queueId);
    }

    // ═══════════════════════════════════════════════════════════
    // AGENT PICKING — called when a call arrives at a queue
    // ═══════════════════════════════════════════════════════════

    /**
     * Pick the next available agent for a call arriving at this queue.
     * Returns empty if no agents are available (call should wait or go to voicemail).
     */
    public Optional<Agent> pickAgent(UUID queueId) {
        Queue queue = queueRepository.findById(queueId).orElse(null);
        if (queue == null || !queue.getIsActive()) return Optional.empty();

        return switch (queue.getStrategy()) {
            case ROUND_ROBIN, LONGEST_IDLE -> pickByPriorityAndAvailability(queueId);
            case SKILLS_BASED -> pickBySkills(queueId, null); // skill from routing metadata
            case RING_ALL -> pickFirstAvailable(queueId); // ring-all handled by FreeSWITCH
            case AI_ROUTED -> pickByPriorityAndAvailability(queueId); // fallback until AI routing impl
        };
    }

    /**
     * Pick with specific skill requirement — for SKILLS_BASED strategy.
     */
    public Optional<Agent> pickAgent(UUID queueId, String requiredSkill) {
        return pickBySkills(queueId, requiredSkill);
    }

    private Optional<Agent> pickByPriorityAndAvailability(UUID queueId) {
        List<QueueMember> available = memberRepository.findAvailableMembersByQueueId(queueId);
        return available.isEmpty() ? Optional.empty() : Optional.of(available.getFirst().getAgent());
    }

    private Optional<Agent> pickBySkills(UUID queueId, String skill) {
        List<QueueMember> available = memberRepository.findAvailableMembersByQueueId(queueId);
        if (skill == null || skill.isBlank()) {
            return available.isEmpty() ? Optional.empty() : Optional.of(available.getFirst().getAgent());
        }

        // Filter members who have the required skill
        return available.stream()
                .map(QueueMember::getAgent)
                .filter(a -> a.getSkills() != null && a.getSkills().contains(skill))
                .findFirst();
    }

    private Optional<Agent> pickFirstAvailable(UUID queueId) {
        return pickByPriorityAndAvailability(queueId);
    }

    // ═══════════════════════════════════════════════════════════
    // QUEUE STATS
    // ═══════════════════════════════════════════════════════════

    public Map<String, Object> getQueueStats(UUID queueId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalMembers", memberRepository.countByQueueId(queueId));
        stats.put("availableMembers", memberRepository.findAvailableMembersByQueueId(queueId).size());
        return stats;
    }
}