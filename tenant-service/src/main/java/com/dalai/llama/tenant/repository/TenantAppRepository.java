package com.dalai.llama.tenant.repository;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.AppType;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantAppRepository extends JpaRepository<TenantApp, UUID> {

/**
     * Delete all apps for a tenant
     */
    @Modifying
    @Query("DELETE FROM TenantApp ta WHERE ta.tenant.id = :tenantId")
    void deleteByTenantId(@Param("tenantId") UUID tenantId);

    Optional<TenantApp> findFirstByTenantId(UUID tenantId);
    // ==================== FIND BY SUBSCRIPTION ====================

    Optional<TenantApp> findBySubscriptionId(UUID subscriptionId);

    boolean existsBySubscriptionId(UUID subscriptionId);

    boolean existsByTenantIdAndSubscriptionId(UUID tenantId, UUID subscriptionId);

    @EntityGraph(attributePaths = {"tenant"})
    Optional<TenantApp> findWithTenantById(UUID id);
    // ==================== FIND BY TENANT ====================

    List<TenantApp> findByTenantId(UUID tenantId);

    List<TenantApp> findByTenantIdAndEnabledTrue(UUID tenantId);

    List<TenantApp> findByTenantIdOrderByDisplayOrderAsc(UUID tenantId);

    List<TenantApp> findByTenantIdAndEnabledTrueOrderByDisplayOrderAsc(UUID tenantId);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndEnabledTrue(UUID tenantId);

    // ==================== FIND BY DID ====================

    Optional<TenantApp> findByDidNumber(String didNumber);

    boolean existsByDidNumber(String didNumber);

    @Query("SELECT ta FROM TenantApp ta WHERE ta.didNumber LIKE :prefix%")
    List<TenantApp> findByDidNumberPrefix(@Param("prefix") String prefix);

    // ==================== FIND BY APP TYPE ====================

    Optional<TenantApp> findByTenantIdAndAppType(UUID tenantId, AppType appType);

    boolean existsByTenantIdAndAppType(UUID tenantId, AppType appType);

    List<TenantApp> findByAppType(AppType appType);

    // ==================== FIND BY SUBDOMAIN ====================

    Optional<TenantApp> findByTenantIdAndSubdomain(UUID tenantId, String subdomain);

    // ==================== FIND BY NAMESPACE ====================

    List<TenantApp> findByNamespace(String namespace);

    @Query("SELECT DISTINCT ta.namespace FROM TenantApp ta WHERE ta.deploymentModel = 'DEDICATED'")
    List<String> findAllDedicatedNamespaces();

    // ==================== FIND BY STATUS ====================

    List<TenantApp> findByDeploymentStatus(ProvisioningTaskStatus status);

    @Query("SELECT ta FROM TenantApp ta WHERE ta.kamailioSynced = false AND ta.enabled = true")
    List<TenantApp> findPendingKamailioSync();

    @Query("SELECT ta FROM TenantApp ta WHERE ta.freepbxSynced = false AND ta.enabled = true")
    List<TenantApp> findPendingFreepbxSync();

    // ==================== FIND BY PRODUCT ====================

    List<TenantApp> findByProductCode(String productCode);

    List<TenantApp> findByProductCodeAndPlanTier(String productCode, String planTier);

    @Query("SELECT COUNT(ta) FROM TenantApp ta WHERE ta.productCode = :productCode AND ta.enabled = true")
    long countActiveByProductCode(@Param("productCode") String productCode);

    // ==================== KEYCLOAK ====================

    @Query("SELECT DISTINCT ta.keycloakClientId FROM TenantApp ta WHERE ta.tenant.id = :tenantId AND ta.enabled = true")
    List<String> findEnabledClientIdsByTenantId(@Param("tenantId") UUID tenantId);

    // ==================== SIP ENDPOINTS ====================

    @Query("SELECT ta FROM TenantApp ta WHERE ta.sipEndpointUsername = :username")
    Optional<TenantApp> findBySipEndpointUsername(@Param("username") String username);

    @Query("SELECT ta FROM TenantApp ta WHERE ta.tenantTrunkUsername = :username")
    Optional<TenantApp> findByTenantTrunkUsername(@Param("username") String username);

    // ==================== UPDATE STATUS ====================

    @Modifying
    @Query("UPDATE TenantApp ta SET ta.deploymentStatus = :status, ta.deployedAt = CURRENT_TIMESTAMP, ta.updatedAt = CURRENT_TIMESTAMP WHERE ta.id = :id")
    void updateDeploymentStatus(@Param("id") UUID id, @Param("status") ProvisioningTaskStatus status);

    @Modifying
    @Query("UPDATE TenantApp ta SET ta.kamailioSynced = true, ta.kamailioSyncedAt = CURRENT_TIMESTAMP WHERE ta.id = :id")
    void markKamailioSynced(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE TenantApp ta SET ta.freepbxSynced = true, ta.freepbxSyncedAt = CURRENT_TIMESTAMP WHERE ta.id = :id")
    void markFreepbxSynced(@Param("id") UUID id);

    // ==================== DELETE ====================


    @Modifying
    @Query("DELETE FROM TenantApp ta WHERE ta.subscriptionId = :subscriptionId")
    void deleteBySubscriptionId(@Param("subscriptionId") UUID subscriptionId);

    // ==================== ANALYTICS ====================

    @Query("SELECT ta.productCode, COUNT(ta) FROM TenantApp ta WHERE ta.enabled = true GROUP BY ta.productCode")
    List<Object[]> countActiveByProduct();

    @Query("SELECT ta.planTier, COUNT(ta) FROM TenantApp ta WHERE ta.enabled = true GROUP BY ta.planTier")
    List<Object[]> countActiveByPlanTier();

    @Query("SELECT SUM(ta.channelTotal) FROM TenantApp ta WHERE ta.namespace = :namespace AND ta.enabled = true")
    Integer sumChannelsByNamespace(@Param("namespace") String namespace);


    List<TenantApp> findAllByTenantId(UUID tenantId);


    /**
     * Find by DID number (with or without + prefix)
     */
    @Query("SELECT ta FROM TenantApp ta WHERE " +
            "(ta.didNumber = :didNumber OR ta.didNumber = CONCAT('+', :didNumber) OR " +
            "REPLACE(ta.didNumber, '+', '') = :didNumber) AND ta.enabled = true")
    Optional<TenantApp> findByDidNumberNormalized(@Param("didNumber") String didNumber);

    /**
     * Find active subscriptions for tenant with specific direction capability
     */
    @Query("SELECT ta FROM TenantApp ta WHERE ta.tenant.id = :tenantId AND ta.enabled = :enabled")
    List<TenantApp> findByTenantIdAndEnabled(@Param("tenantId") UUID tenantId, @Param("enabled") boolean enabled);


    /**
     * Find all that need Kamailio sync
     */
    @Query("SELECT ta FROM TenantApp ta WHERE ta.kamailioSynced = false AND ta.enabled = true")
    List<TenantApp> findAllNeedingKamailioSync();

    /**
     * Find all that need FreePBX sync
     */
    @Query("SELECT ta FROM TenantApp ta WHERE ta.freepbxSynced = false AND ta.enabled = true")
    List<TenantApp> findAllNeedingFreepbxSync();


    /**
     * Find subscriptions expiring soon
     */
    @Query("SELECT ta FROM TenantApp ta WHERE ta.tenant.expiresAt IS NOT NULL AND " +
            "ta.tenant.expiresAt < CURRENT_TIMESTAMP + 7 DAY")
    List<TenantApp> findExpiringSoon();

    /**
     * Find all shared (non-dedicated) tenant apps that are fully provisioned.
     * Used by IstioHostReconciler to build desired Istio host state.
     */
    List<TenantApp> findAllByDeploymentStatusAndDedicatedInfrastructure(
            ProvisioningTaskStatus status, Boolean dedicated);

    /**
     * Find apps by deployment status, eagerly fetching the parent tenant.
     * Used by IstioRouteReconciler.reconcileAll() which runs outside any
     * transaction and accesses tenant.getSlug() — without JOIN FETCH this
     * would throw LazyInitializationException.
     */
    @Query("SELECT a FROM TenantApp a JOIN FETCH a.tenant WHERE a.deploymentStatus = :status")
    List<TenantApp> findByDeploymentStatusWithTenant(@Param("status") ProvisioningTaskStatus status);
}

