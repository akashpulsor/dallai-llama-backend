package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.SipTrunk;
import com.dalai.llama.product.domain.entity.enums.SipProvider;
import com.dalai.llama.product.domain.entity.enums.SipTrunkStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SipTrunkRepository extends JpaRepository<SipTrunk, UUID> {

    List<SipTrunk> findByTenantId(UUID tenantId);

    List<SipTrunk> findByProvider(SipProvider provider);

    List<SipTrunk> findByStatus(SipTrunkStatus status);

    Optional<SipTrunk> findByDidwwTrunkId(String didwwTrunkId);

    /**
     * Find platform trunk (tenantId = NULL, status = ACTIVE)
     */
    @Query("SELECT t FROM SipTrunk t WHERE t.tenantId IS NULL AND t.status = 'ACTIVE' ORDER BY t.createdAt ASC")
    Optional<SipTrunk> findPlatformTrunk();

    /**
     * Find platform trunk by provider
     */
    @Query("SELECT t FROM SipTrunk t WHERE t.tenantId IS NULL AND t.provider = :provider AND t.status = 'ACTIVE'")
    Optional<SipTrunk> findPlatformTrunkByProvider(@Param("provider") SipProvider provider);


    /**
     * Find active trunks for tenant
     */
    List<SipTrunk> findByTenantIdAndStatus(UUID tenantId, SipTrunkStatus status);

    /**
     * Find healthy platform trunks
     */
    @Query("SELECT t FROM SipTrunk t WHERE t.tenantId IS NULL AND t.status = 'ACTIVE' AND t.isHealthy = true")
    List<SipTrunk> findHealthyPlatformTrunks();
}
