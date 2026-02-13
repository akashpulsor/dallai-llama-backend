package com.dalai.llama.tenant.repository;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.AppType;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
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
     * Find all enabled apps for a tenant, ordered by display order
     */
    List<TenantApp> findByTenantIdAndEnabledTrueOrderByDisplayOrderAsc(UUID tenantId);

    /**
     * Find all apps for a tenant (including disabled)
     */
    List<TenantApp> findByTenantIdOrderByDisplayOrderAsc(UUID tenantId);

    /**
     * Find app by tenant and app type
     */
    Optional<TenantApp> findByTenantIdAndAppType(UUID tenantId, AppType appType);

    /**
     * Find app by tenant and subdomain
     */
    Optional<TenantApp> findByTenantIdAndSubdomain(UUID tenantId, String subdomain);

    /**
     * Check if app exists for tenant
     */
    boolean existsByTenantIdAndAppType(UUID tenantId, AppType appType);

    /**
     * Get all distinct Keycloak client IDs for enabled apps
     */
    @Query("SELECT DISTINCT ta.keycloakClientId FROM TenantApp ta WHERE ta.tenant.id = :tenantId AND ta.enabled = true")
    List<String> findEnabledClientIdsByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Update deployment status
     */
    @Modifying
    @Query("UPDATE TenantApp ta SET ta.deploymentStatus = :status, ta.deployedAt = CURRENT_TIMESTAMP, ta.updatedAt = CURRENT_TIMESTAMP WHERE ta.id = :id")
    void updateDeploymentStatus(@Param("id") UUID id, @Param("status") ProvisioningTaskStatus status);

    /**
     * Delete all apps for a tenant
     */
    @Modifying
    @Query("DELETE FROM TenantApp ta WHERE ta.tenant.id = :tenantId")
    void deleteByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Count enabled apps for tenant
     */
    long countByTenantIdAndEnabledTrue(UUID tenantId);

    // Get all apps for a specific tenant
    List<TenantApp> findByTenantId(UUID tenantId);


}

