package com.dalai.llama.agent.service;

import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.events.AgentEventsProducer;
import com.dalai.llama.agent.repository.AgentRepository;
import com.dalai.llama.agent.repository.CallSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {
    
    private final AgentRepository agentRepository;
    private final CallSessionRepository callSessionRepository;
    private final AgentEventsProducer eventsProducer;
    private final AgentEventLogService eventLogService;

    @Transactional
    public Agent createAgent(Agent agent) {
        log.info("Creating agent: {} for tenant: {}", agent.getUsername(), agent.getTenantId());
        Agent saved = agentRepository.save(agent);
        eventsProducer.publishAgentEvent(saved.getTenantId(), "AGENT_CREATED", saved);
        eventLogService.logEvent(saved.getId(), null, "AGENT_CREATED", "Agent created", null);
        return saved;
    }

    @Transactional
    public Agent updateAgent(Agent agent) {
        log.info("Updating agent: {} (ID: {})", agent.getUsername(), agent.getId());
        Agent saved = agentRepository.save(agent);
        eventsProducer.publishAgentEvent(saved.getTenantId(), "AGENT_UPDATED", saved);
        return saved;
    }

    public List<Agent> findByTenant(String tenantId) {
        return agentRepository.findByTenantId(tenantId);
    }

    public Optional<Agent> findById(Long id) {
        return agentRepository.findById(id);
    }

    public Optional<Agent> findByExternalId(String externalId) {
        return agentRepository.findByExternalId(externalId);
    }

    public List<Agent> findAvailableAgents(String tenantId) {
        return agentRepository.findAvailableAgents(tenantId);
    }

    public List<Agent> findOnlineAgents(String tenantId) {
        return agentRepository.findOnlineAgents(tenantId);
    }

    @Transactional
    public void setOnlineStatus(Long agentId, boolean online) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            agent.setOnline(online);
            agent.setLastSeenAt(OffsetDateTime.now());
            if (!online) {
                agent.setStatus(Agent.AgentStatus.OFFLINE);
                agent.setAvailable(false);
            } else {
                agent.setStatus(Agent.AgentStatus.ONLINE);
            }
            agentRepository.save(agent);
            eventsProducer.publishAgentEvent(agent.getTenantId(), "AGENT_STATUS_CHANGED", agent);
            eventLogService.logEvent(agentId, null, "STATUS_CHANGE", 
                "Agent status changed to " + (online ? "ONLINE" : "OFFLINE"), null);
        });
    }

    @Transactional
    public void setAvailability(Long agentId, Agent.AvailabilityStatus availabilityStatus) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            agent.setAvailabilityStatus(availabilityStatus);
            agent.setAvailable(availabilityStatus == Agent.AvailabilityStatus.AVAILABLE);
            agentRepository.save(agent);
            eventsProducer.publishAgentEvent(agent.getTenantId(), "AGENT_AVAILABILITY_CHANGED", agent);
            eventLogService.logEvent(agentId, null, "AVAILABILITY_CHANGE", 
                "Availability changed to " + availabilityStatus, null);
        });
    }

    @Transactional
    public void updateLastSeen(Long agentId) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            agent.updateLastSeen();
            agentRepository.save(agent);
        });
    }

    public boolean canAcceptCall(Long agentId) {
        Optional<Agent> agentOpt = agentRepository.findById(agentId);
        if (agentOpt.isEmpty()) {
            return false;
        }
        
        Agent agent = agentOpt.get();
        if (!agent.isAvailableForCall()) {
            return false;
        }
        
        long activeCallsCount = callSessionRepository.countActiveCallsByAgent(agentId);
        return activeCallsCount < agent.getMaxConcurrentCalls();
    }

    @Transactional
    public void incrementCallStats(Long agentId, long durationSeconds) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            agent.incrementCallStats(durationSeconds);
            agentRepository.save(agent);
        });
    }
}
