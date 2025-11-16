package com.dalai.llama.agent.repository;

import com.dalai.llama.agent.entity.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {
    List<AgentSession> findByAgentIdAndIsActive(Long agentId, Boolean isActive);
    Optional<AgentSession> findBySessionId(String sessionId);
    List<AgentSession> findByTenantIdAndIsActive(String tenantId, Boolean isActive);
}
