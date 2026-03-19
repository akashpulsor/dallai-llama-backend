package com.dalai.llama.pbx.core.repository.core;


import com.dalai.llama.pbx.core.domain.entity.core.SipTrunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SIP trunks — outbound trunk provider configuration.
 *
 * Read paths:
 *   - CallAuthorizationService (outbound) → resolve trunk for outbound calls
 *   - TrunkController → CRUD listing
 *
 * Write paths:
 *   - TrunkController CRUD → TrunkService also syncs uacreg table
 *   - KamailioWriteController → tenant-service inserts during provisioning
 */
@Repository
public interface SipTrunkRepository extends JpaRepository<SipTrunk, UUID> {

    List<SipTrunk> findByTenantId(UUID tenantId);

    List<SipTrunk> findByTenantIdAndIsActiveTrue(UUID tenantId);

    Optional<SipTrunk> findByTenantIdAndName(UUID tenantId, String name);

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    /**
     * Find trunk by dispatcher set — when Kamailio needs to route outbound
     * via a specific dispatcher set, this resolves the trunk config.
     */
    Optional<SipTrunk> findByDispatcherSetId(Integer dispatcherSetId);

    long countByTenantId(UUID tenantId);
}
