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
     * Find the best active platform trunk for a given country.
     * Platform trunks have tenantId = NULL.
     * Selection order: healthy first, then highest priority (lowest priority value),
     * then oldest (most stable) — gives a deterministic pick when multiple trunks match.
     *
     * Returns empty if none found — caller should handle the empty Optional
     * with an actionable error so ops can seed the missing trunk.
     */
    @Query("""
            SELECT t FROM SipTrunk t
            WHERE t.tenantId IS NULL
              AND t.country = :country
              AND t.status = com.dalai.llama.product.domain.entity.enums.SipTrunkStatus.ACTIVE
            ORDER BY t.isHealthy DESC, t.priority ASC, t.createdAt ASC
            """)
    List<SipTrunk> findPlatformTrunksForCountry(@Param("country") String country);

    /**
     * Convenience: returns the first (best) match from {@link #findPlatformTrunksForCountry}.
     */
    default Optional<SipTrunk> findBestPlatformTrunkForCountry(String country) {
        return findPlatformTrunksForCountry(country).stream().findFirst();
    }

    /**
     * Find platform trunk by provider (any country).
     * Use only when country-specific routing isn't relevant — e.g. admin lookups.
     */
    @Query("SELECT t FROM SipTrunk t WHERE t.tenantId IS NULL AND t.provider = :provider AND t.status = 'ACTIVE'")
    Optional<SipTrunk> findPlatformTrunkByProvider(@Param("provider") SipProvider provider);

    /**
     * Find active trunks for tenant
     */
    List<SipTrunk> findByTenantIdAndStatus(UUID tenantId, SipTrunkStatus status);

    /**
     * Find healthy platform trunks (any country).
     */
    @Query("SELECT t FROM SipTrunk t WHERE t.tenantId IS NULL AND t.status = 'ACTIVE' AND t.isHealthy = true")
    List<SipTrunk> findHealthyPlatformTrunks();
}