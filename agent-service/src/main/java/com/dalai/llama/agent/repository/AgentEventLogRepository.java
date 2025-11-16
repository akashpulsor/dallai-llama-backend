package com.dalai.llama.agent.repository;

import com.dalai.llama.agent.entity.AgentEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AgentEventLogRepository extends JpaRepository<AgentEventLog, Long> {
    List<AgentEventLog> findByAgentIdOrderByCreatedAtDesc(Long agentId);
    List<AgentEventLog> findByTenantIdAndEventType(String tenantId, String eventType);
    List<AgentEventLog> findByCallSessionId(Long callSessionId);
}
