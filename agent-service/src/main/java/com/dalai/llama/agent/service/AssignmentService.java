package com.dalai.llama.agent.service;

import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.events.AgentEventsProducer;
import com.dalai.llama.agent.repository.AgentRepository;
import com.dalai.llama.agent.repository.CallSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentService {
    
    private final AgentRepository agentRepository;
    private final CallSessionRepository callSessionRepository;
    private final AgentEventsProducer eventsProducer;
    
    @Value("${app.agent.assignment.strategy:SKILL_BASED}")
    private String assignmentStrategy;

    public Optional<Agent> assignAgent(String tenantId, String queueId, List<String> requiredSkills) {
        log.info("Finding agent for tenant: {}, queue: {}, strategy: {}", tenantId, queueId, assignmentStrategy);
        
        List<Agent> availableAgents = agentRepository.findAvailableAgents(tenantId);
        
        // Filter by queue membership
        if (queueId != null) {
            availableAgents = availableAgents.stream()
                .filter(a -> a.getQueueMemberships().contains(queueId))
                .collect(Collectors.toList());
        }
        
        // Filter by skills
        if (requiredSkills != null && !requiredSkills.isEmpty()) {
            availableAgents = availableAgents.stream()
                .filter(a -> a.getSkills().containsAll(requiredSkills))
                .collect(Collectors.toList());
        }
        
        // Filter agents who can accept more calls
        availableAgents = availableAgents.stream()
            .filter(a -> {
                long activeCalls = callSessionRepository.countActiveCallsByAgent(a.getId());
                return activeCalls < a.getMaxConcurrentCalls();
            })
            .collect(Collectors.toList());
        
        if (availableAgents.isEmpty()) {
            log.warn("No available agents found for tenant: {}, queue: {}", tenantId, queueId);
            return Optional.empty();
        }
        
        // Apply assignment strategy
        return switch (assignmentStrategy) {
            case "LEAST_ACTIVE" -> findLeastActiveAgent(availableAgents);
            case "LONGEST_IDLE" -> findLongestIdleAgent(availableAgents);
            case "ROUND_ROBIN" -> findRoundRobinAgent(availableAgents);
            default -> findLongestIdleAgent(availableAgents);
        };
    }

    private Optional<Agent> findLeastActiveAgent(List<Agent> agents) {
        return agents.stream()
            .min(Comparator.comparingLong(a -> 
                callSessionRepository.countActiveCallsByAgent(a.getId())));
    }

    private Optional<Agent> findLongestIdleAgent(List<Agent> agents) {
        return agents.stream()
            .min(Comparator.comparing(Agent::getLastCallAt, 
                Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private Optional<Agent> findRoundRobinAgent(List<Agent> agents) {
        return agents.stream()
            .min(Comparator.comparing(Agent::getId));
    }

    public void publishAssignment(String tenantId, String callId, Agent agent, String wssUrl) {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("callId", callId);
        payload.put("agentId", agent.getId());
        payload.put("agentExternalId", agent.getExternalId());
        payload.put("agentUsername", agent.getUsername());
        payload.put("wssUrl", wssUrl);
        
        eventsProducer.publishEvent(tenantId, "ASSIGNMENT_MADE", payload);
        log.info("Published assignment: agent {} assigned to call {}", agent.getId(), callId);
    }
}
