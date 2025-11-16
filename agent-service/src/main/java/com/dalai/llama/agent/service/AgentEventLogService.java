package com.dalai.llama.agent.service;

import com.dalai.llama.agent.entity.AgentEventLog;
import com.dalai.llama.agent.repository.AgentEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventLogService {
    
    private final AgentEventLogRepository repository;
    
    @Value("${app.tenant.id:default}")
    private String tenantId;

    @Transactional
    public void logEvent(Long agentId, Long callSessionId, String eventType, String message, Map<String, Object> payload) {
        AgentEventLog event = AgentEventLog.builder()
            .agentId(agentId)
            .callSessionId(callSessionId)
            .tenantId(tenantId)
            .eventType(eventType)
            .message(message)
            .payload(payload)
            .build();
        
        repository.save(event);
        log.debug("Event logged: {} for agent: {}", eventType, agentId);
    }

    public List<AgentEventLog> getAgentEvents(Long agentId) {
        return repository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    public List<AgentEventLog> getCallEvents(Long callSessionId) {
        return repository.findByCallSessionId(callSessionId);
    }
}
