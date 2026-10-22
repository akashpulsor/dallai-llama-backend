package com.dalai.llama.billing.repository;

import com.dalai.llama.billing.domain.entity.CreatorPayout;
import com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorPayoutRepository extends JpaRepository<CreatorPayout, UUID> {

    /** Opens a project's payout once: the verify callback and the capture webhook can both get
     * here for the same lock, and must not race each other into a constraint error. */
    @Modifying
    @Query(value = """
            INSERT INTO creator_payout (id, tenant_id, project_id, status, currency, attempts, created_at, updated_at)
            VALUES (:id, :tenantId, :projectId, 'QUEUED', 'INR', 0, now(), now())
            ON CONFLICT (project_id) DO NOTHING
            """, nativeQuery = true)
    int openIfAbsent(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("projectId") UUID projectId);

    Optional<CreatorPayout> findByGatewayPayoutId(String gatewayPayoutId);

    List<CreatorPayout> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<CreatorPayout> findByStatusIn(Collection<CreatorPayoutStatus> statuses);

    List<CreatorPayout> findTop200ByOrderByCreatedAtDesc();
}
