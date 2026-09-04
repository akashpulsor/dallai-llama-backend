package com.dalai.llama.tenant.repository;

import com.dalai.llama.tenant.domain.entity.UserCredentialDelivery;
import com.dalai.llama.tenant.domain.entity.enums.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserCredentialDeliveryRepository extends JpaRepository<UserCredentialDelivery, UUID> {

    Optional<UserCredentialDelivery> findFirstByKeycloakUserIdAndStatusInOrderByCreatedAtDesc(
            String keycloakUserId, Collection<DeliveryStatus> statuses);

    Optional<UserCredentialDelivery> findFirstByTenantUserIdAndStatusInOrderByCreatedAtDesc(
            UUID tenantUserId, Collection<DeliveryStatus> statuses);

    Optional<UserCredentialDelivery> findFirstByTenantUserIdOrderByCreatedAtDesc(UUID tenantUserId);

    List<UserCredentialDelivery> findByStatusInAndExpiresAtBefore(
            Collection<DeliveryStatus> statuses, OffsetDateTime cutoff);

    Optional<UserCredentialDelivery> findByIdAndTenantId(UUID id, UUID tenantId);

    @Modifying
    @Query("""
        UPDATE UserCredentialDelivery d
           SET d.status = :revokedStatus, d.tempPasswordEnc = NULL
         WHERE d.tenantUserId = :tenantUserId
           AND d.status IN :activeStatuses
        """)
    int revokeAllActive(@Param("tenantUserId") UUID tenantUserId,
                        @Param("activeStatuses") Collection<DeliveryStatus> activeStatuses,
                        @Param("revokedStatus") DeliveryStatus revokedStatus);
}
