package com.dalai.llama.agent.repository;

import com.dalai.llama.agent.entity.Queue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface QueueRepository extends JpaRepository<Queue, Long> {
    Optional<Queue> findByQueueId(String queueId);
    List<Queue> findByTenantIdAndIsActive(String tenantId, Boolean isActive);
    Optional<Queue> findByTenantIdAndQueueName(String tenantId, String queueName);
}
