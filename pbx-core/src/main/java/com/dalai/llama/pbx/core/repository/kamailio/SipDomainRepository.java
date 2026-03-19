package com.dalai.llama.pbx.core.repository.kamailio;


import com.dalai.llama.pbx.core.domain.entity.kamailio.SipDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Kamailio domain table.
 *
 * Each tenant gets a SIP domain (e.g., "tenant-acme.dalaillama.in").
 * Kamailio domain module reads this directly to validate Request-URI.
 * After INSERT, tenant-service calls /api/v1/write/kamailio/reload
 * which triggers kamcmd domain.reload.
 */
@Repository
public interface SipDomainRepository extends JpaRepository<SipDomain, Integer> {

    Optional<SipDomain> findByDomain(String domain);

    boolean existsByDomain(String domain);

    List<SipDomain> findByTenantId(UUID tenantId);

    Optional<SipDomain> findByTenantIdAndIsActiveTrue(UUID tenantId);

    @Modifying
    void deleteByTenantId(UUID tenantId);

    @Modifying
    @Query("UPDATE SipDomain d SET d.isActive = :active, d.lastModified = CURRENT_TIMESTAMP " +
            "WHERE d.tenantId = :tenantId")
    int updateActiveByTenantId(UUID tenantId, boolean active);
}