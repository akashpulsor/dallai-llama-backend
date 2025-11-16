package com.dalai.llama.agent.repository;

import com.dalai.llama.agent.entity.CallSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface CallSessionRepository extends JpaRepository<CallSession, Long> {
    Optional<CallSession> findByCallId(String callId);
    List<CallSession> findByAgentIdAndStatus(Long agentId, CallSession.CallStatus status);
    List<CallSession> findByTenantIdAndStatus(String tenantId, CallSession.CallStatus status);
    
    @Query("SELECT c FROM CallSession c WHERE c.agentId = ?1 AND c.status IN ('RINGING', 'ANSWERED', 'ON_HOLD')")
    List<CallSession> findActiveCallsByAgent(Long agentId);
    
    @Query("SELECT COUNT(c) FROM CallSession c WHERE c.agentId = ?1 AND c.status IN ('ANSWERED', 'ON_HOLD')")
    long countActiveCallsByAgent(Long agentId);
}
