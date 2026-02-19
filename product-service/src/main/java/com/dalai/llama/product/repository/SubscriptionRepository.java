package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.Subscription;
import com.dalai.llama.product.domain.entity.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /**
     * Find subscription by tenant and product
     */
    List<Subscription> findByTenantIdAndProductId(UUID tenantId, UUID productId);

    /**
     * Find all subscriptions for tenant
     */
    List<Subscription> findByTenantId(UUID tenantId);

    /**
     * Find active subscriptions for tenant
     */
    List<Subscription> findByTenantIdAndStatus(UUID tenantId, SubscriptionStatus status);

    /**
     * Find all active subscriptions for tenant
     */
    @Query("SELECT s FROM Subscription s WHERE s.tenantId = :tenantId AND s.status = 'ACTIVE'")
    List<Subscription> findActiveByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Find subscriptions needing provisioning retry
     */
    @Query("SELECT s FROM Subscription s WHERE s.status = 'PENDING_PROVISION' " +
            "AND (s.didProvisioned = false OR s.sipEndpointCreated = false OR s.channelsAllocated = false)")
    List<Subscription> findPendingProvisioning();

    /**
     * Find expiring subscriptions (for renewal reminders)
     */
    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.expiresAt <= :before")
    List<Subscription> findExpiringSoon(@Param("before") Instant before);

    /**
     * Count active subscriptions for tenant
     */
    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.tenantId = :tenantId AND s.status = 'ACTIVE'")
    long countActiveByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Find single subscription by tenant and product (legacy - use list version)
     */
    @Query("SELECT s FROM Subscription s WHERE s.tenantId = :tenantId AND s.product.id = :productId ORDER BY s.createdAt DESC")
    Optional<Subscription> findFirstByTenantIdAndProductId(@Param("tenantId") UUID tenantId, @Param("productId") UUID productId);

    /**
     * Find by DID ID
     */
    Optional<Subscription> findByDidId(UUID didId);

    /**
     * Find by channel bundle ID
     */
    Optional<Subscription> findByChannelBundleId(UUID channelBundleId);

    /**
     * Find by SIP trunk ID
     */
    Optional<Subscription> findByTenantSipTrunkId(UUID tenantSipTrunkId);


    /**
     * Check if tenant has any active subscription
     */
    default boolean hasActiveSubscription(UUID tenantId) {
        return countActiveByTenantId(tenantId) > 0;
    }


}