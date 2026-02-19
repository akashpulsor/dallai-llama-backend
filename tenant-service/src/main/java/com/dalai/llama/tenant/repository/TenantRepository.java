package com.dalai.llama.tenant.repository;


import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);


    /* ========= Used by HealthCheckScheduler ========= */
    @Query("""
        select t
        from Tenant t
        where t.status = :status
          and t.deletedAt is null
    """)
    List<Tenant> findByStatus(TenantStatus status);

    /* ========= Convenience methods ========= */

    default List<Tenant> findActiveTenants() {
        return findByStatus(TenantStatus.ACTIVE);
    }

    default List<Tenant> findTenantsInErrorState() {
        return findByStatus(TenantStatus.ERROR);
    }

    List<Tenant> findByExpiresAtBefore(OffsetDateTime now);
}
