package com.dalai.llama.tenant.repository;

import com.dalai.llama.tenant.domain.entity.TenantUser;
import com.dalai.llama.tenant.domain.entity.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantUserRepository extends JpaRepository<TenantUser, UUID> {

    Optional<TenantUser> findByKeycloakUserId(String keycloakUserId);

    Optional<TenantUser> findByTenantIdAndPrimaryRole(UUID tenantId, UserRole primaryRole);

    Optional<TenantUser> findFirstByTenantIdAndEmail(UUID tenantId, String email);

    List<TenantUser> findAllByTenantIdAndPrimaryRole(UUID tenantId, UserRole primaryRole);

    List<TenantUser> findAllByTenantId(UUID tenantId);

    Optional<TenantUser> findByIdAndTenantId(UUID id, UUID tenantId);
}
