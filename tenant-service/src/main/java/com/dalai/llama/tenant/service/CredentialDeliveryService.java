package com.dalai.llama.tenant.service;

import com.dalai.llama.tenant.dto.request.ProvisionTenantUserRequest;
import com.dalai.llama.tenant.dto.response.AdminCredentials;
import com.dalai.llama.tenant.dto.response.ProvisionedUserResult;

import java.util.Optional;
import java.util.UUID;

public interface CredentialDeliveryService {

    ProvisionedUserResult provisionTenantUser(ProvisionTenantUserRequest request);

    Optional<AdminCredentials> getAdminCredentials(UUID tenantId);

    void recordLogin(String keycloakUserId);

    void changePassword(UUID tenantUserId, String newPassword, String requesterSubject);

    void deprovisionTenantUser(UUID tenantUserId, String reason, String requesterSubject);
}
