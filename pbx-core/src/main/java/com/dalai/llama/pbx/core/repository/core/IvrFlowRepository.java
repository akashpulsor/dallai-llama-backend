package com.dalai.llama.pbx.core.repository.core;


import com.dalai.llama.pbx.core.domain.entity.core.IvrFlow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * IVR flows — visual IVR builder output stored as JSONB.
 *
 * Read paths:
 *   - Dialplan generation: when routing target is IVR, the flow_json
 *     is used to build FreeSWITCH XML with menu prompts and DTMF routes.
 *   - RoutingController → list available IVR flows for routing policy config.
 *
 * Write paths:
 *   - RoutingController CRUD
 */
@Repository
public interface IvrFlowRepository extends JpaRepository<IvrFlow, UUID> {

    List<IvrFlow> findByTenantId(UUID tenantId);

    List<IvrFlow> findByTenantIdAndIsActiveTrue(UUID tenantId);

    Optional<IvrFlow> findByTenantIdAndName(UUID tenantId, String name);

    long countByTenantId(UUID tenantId);
}
