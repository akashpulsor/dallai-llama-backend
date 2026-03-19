package com.dalai.llama.pbx.core.repository.core;


import com.dalai.llama.pbx.core.domain.entity.core.Queue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Queue table — call queues with routing strategies.
 *
 * Read paths:
 *   - CallAuthorizationService → routing target resolves to QUEUE:{queueId}
 *   - FreeSwitchXmlController → dialplan may reference queue for call distribution
 *   - SupervisorController → queue stats (waiting count, agents available)
 *
 * Write paths:
 *   - QueueController CRUD
 */
@Repository
public interface QueueRepository extends JpaRepository<Queue, UUID> {

    List<Queue> findByTenantId(UUID tenantId);

    List<Queue> findByTenantIdAndIsActiveTrue(UUID tenantId);

    Optional<Queue> findByTenantIdAndName(UUID tenantId, String name);

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndIsActiveTrue(UUID tenantId);
}