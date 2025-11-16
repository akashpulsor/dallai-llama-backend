package com.dalai.llama.agent.repository;

import com.dalai.llama.agent.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRepository extends JpaRepository<Agent, Long> {
    List<Agent> findByTenantId(String tenantId);
    Optional<Agent> findByExternalId(String externalId);
    Optional<Agent> findByTenantIdAndUsername(String tenantId, String username);
    
    @Query("SELECT a FROM Agent a WHERE a.tenantId = ?1 AND a.online = true AND a.available = true")
    List<Agent> findAvailableAgents(String tenantId);
    
    @Query("SELECT a FROM Agent a WHERE a.tenantId = ?1 AND a.status = 'ONLINE'")
    List<Agent> findOnlineAgents(String tenantId);
    
    @Query("SELECT COUNT(a) FROM Agent a WHERE a.tenantId = ?1 AND a.online = true")
    long countOnlineAgents(String tenantId);
}
