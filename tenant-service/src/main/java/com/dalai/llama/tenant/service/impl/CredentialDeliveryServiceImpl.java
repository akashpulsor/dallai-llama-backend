package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantUser;
import com.dalai.llama.tenant.domain.entity.UserCredentialDelivery;
import com.dalai.llama.tenant.domain.entity.enums.DeliveryStatus;
import com.dalai.llama.tenant.domain.entity.enums.UserRole;
import com.dalai.llama.tenant.domain.entity.enums.UserStatus;
import com.dalai.llama.tenant.domain.event.CredentialDeliveryEvent;
import com.dalai.llama.tenant.dto.request.ProvisionTenantUserRequest;
import com.dalai.llama.tenant.dto.response.AdminCredentials;
import com.dalai.llama.tenant.dto.response.ProvisionedUserResult;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.repository.TenantUserRepository;
import com.dalai.llama.tenant.repository.UserCredentialDeliveryRepository;
import com.dalai.llama.tenant.security.CredentialEncryptor;
import com.dalai.llama.tenant.service.CredentialDeliveryService;
import com.dalai.llama.tenant.service.KeycloakRealmService;
import com.dalai.llama.tenant.util.PasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialDeliveryServiceImpl implements CredentialDeliveryService {

    private static final Set<DeliveryStatus> ACTIVE_STATUSES =
            Set.of(DeliveryStatus.PENDING, DeliveryStatus.VIEWED);

    private final TenantUserRepository tenantUserRepository;
    private final UserCredentialDeliveryRepository deliveryRepository;
    private final TenantRepository tenantRepository;
    private final KeycloakRealmService keycloakRealmService;
    private final CredentialEncryptor encryptor;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${dalaillama.credentials.ttl-hours:72}")
    private long ttlHours;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Override
    @Transactional
    public ProvisionedUserResult provisionTenantUser(ProvisionTenantUserRequest request) {
        validateProvisionRequest(request);

        Tenant tenant = tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + request.tenantId()));

        TenantUser tenantUser = tenantUserRepository
                .findFirstByTenantIdAndEmail(request.tenantId(), request.email())
                .map(existing -> updateExistingTenantUser(existing, request))
                .orElseGet(() -> createTenantUser(tenant, request));

        UserCredentialDelivery delivery = findActiveDelivery(tenantUser)
                .orElseGet(() -> issueFreshCredential(tenant, tenantUser, true, request.createdBy()));

        return ProvisionedUserResult.builder()
                .tenantUserId(tenantUser.getId())
                .keycloakUserId(tenantUser.getKeycloakUserId())
                .credentialDeliveryId(delivery.getId())
                .loginUrl(delivery.getLoginUrl())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdminCredentials> getAdminCredentials(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            return Optional.empty();
        }

        TenantUser admin = tenantUserRepository.findByTenantIdAndPrimaryRole(tenantId, UserRole.ADMIN)
                .orElse(null);

        if (admin == null) {
            if (tenant.getAdminUserEmail() == null) {
                return Optional.empty();
            }
            return Optional.of(new AdminCredentials(
                    tenant.getAdminUserEmail(),
                    null,
                    buildLoginUrl(tenant, UserRole.ADMIN)));
        }

        UserCredentialDelivery latestDelivery =
                deliveryRepository.findFirstByTenantUserIdOrderByCreatedAtDesc(admin.getId())
                        .orElse(null);

        String loginUrl = latestDelivery != null
                ? latestDelivery.getLoginUrl()
                : buildLoginUrl(tenant, UserRole.ADMIN);

        String plaintextPassword = latestDelivery != null
                ? decryptIfAvailable(latestDelivery)
                : null;

        return Optional.of(new AdminCredentials(admin.getEmail(), plaintextPassword, loginUrl));
    }

    @Override
    @Transactional
    public void recordLogin(String keycloakUserId) {
        OffsetDateTime now = OffsetDateTime.now();

        tenantUserRepository.findByKeycloakUserId(keycloakUserId).ifPresent(tenantUser -> {
            if (tenantUser.getFirstLoginAt() == null) {
                tenantUser.setFirstLoginAt(now);
            }
            tenantUser.setLastLoginAt(now);
            tenantUser.setLoginCount((tenantUser.getLoginCount() == null ? 0L : tenantUser.getLoginCount()) + 1);
            tenantUser.setStatus(UserStatus.ACTIVE);
            tenantUser.setMustChangePassword(false);
            tenantUserRepository.save(tenantUser);
            log.info("Registered tenant user login: tenantUser={} kcUser={}",
                    tenantUser.getId(), keycloakUserId);
        });

        deliveryRepository.findFirstByKeycloakUserIdAndStatusInOrderByCreatedAtDesc(
                keycloakUserId, ACTIVE_STATUSES
        ).ifPresent(delivery -> {
            DeliveryStatus previousStatus = delivery.getStatus();
            delivery.setStatus(DeliveryStatus.CONSUMED);
            delivery.setTempPasswordEnc(null);
            delivery.setConsumedAt(now);
            deliveryRepository.save(delivery);

            publishEvent(delivery.getId(), delivery.getTenantId(), delivery.getTenantUserId(),
                    keycloakUserId, previousStatus, DeliveryStatus.CONSUMED, keycloakUserId);
        });
    }

    @Override
    @Transactional
    public void changePassword(UUID tenantUserId, String newPassword, String requesterSubject) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("newPassword is required");
        }

        TenantUser tenantUser = tenantUserRepository.findById(tenantUserId)
                .orElseThrow(() -> new IllegalArgumentException("TenantUser not found: " + tenantUserId));
        Tenant tenant = tenantRepository.findById(tenantUser.getTenantId())
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantUser.getTenantId()));

        keycloakRealmService.resetUserPassword(
                tenant.getKeycloakRealmName(),
                tenantUser.getKeycloakUserId(),
                newPassword,
                false);

        deliveryRepository.revokeAllActive(
                tenantUserId, ACTIVE_STATUSES, DeliveryStatus.REVOKED);

        tenantUser.setStatus(UserStatus.ACTIVE);
        tenantUser.setMustChangePassword(false);
        tenantUserRepository.save(tenantUser);

        saveDelivery(tenant, tenantUser, newPassword, DeliveryStatus.VIEWED, requesterSubject);
        log.info("Changed password for TenantUser={} actor={}", tenantUserId, requesterSubject);
    }

    @Override
    @Transactional
    public void deprovisionTenantUser(UUID tenantUserId, String reason, String requesterSubject) {
        TenantUser tenantUser = tenantUserRepository.findById(tenantUserId)
                .orElseThrow(() -> new IllegalArgumentException("TenantUser not found: " + tenantUserId));

        Tenant tenant = tenantRepository.findById(tenantUser.getTenantId())
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantUser.getTenantId()));

        int revoked = deliveryRepository.revokeAllActive(
                tenantUserId, ACTIVE_STATUSES, DeliveryStatus.REVOKED);
        keycloakRealmService.disableUser(tenant.getKeycloakRealmName(), tenantUser.getKeycloakUserId());

        tenantUser.setStatus(UserStatus.DISABLED);
        tenantUserRepository.save(tenantUser);

        if (revoked > 0) {
            publishEvent(null, tenantUser.getTenantId(), tenantUserId,
                    tenantUser.getKeycloakUserId(), null, DeliveryStatus.REVOKED, requesterSubject);
        }

        log.info("Deprovisioned TenantUser={} reason={}", tenantUserId, reason);
    }

    private TenantUser createTenantUser(Tenant tenant, ProvisionTenantUserRequest request) {
        String username = defaultUsername(request);
        String temporaryPassword = PasswordGenerator.generate(14);
        String keycloakUserId = keycloakRealmService.createTenantUser(
                tenant.getKeycloakRealmName(),
                tenant.getId(),
                username,
                request.email(),
                buildDisplayName(request.firstName(), request.lastName()),
                temporaryPassword,
                request.primaryRole());

        tenantUserRepository.findByKeycloakUserId(keycloakUserId).ifPresent(conflict -> {
            throw new IllegalStateException(
                    "Keycloak user " + keycloakUserId + " is already linked to TenantUser " + conflict.getId());
        });

        TenantUser tenantUser = TenantUser.builder()
                .tenantId(tenant.getId())
                .keycloakUserId(keycloakUserId)
                .username(username)
                .email(request.email())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .primaryRole(request.primaryRole())
                .rolesCsv(request.additionalRoles() != null ? String.join(",", request.additionalRoles()) : null)
                .status(UserStatus.PENDING_FIRST_LOGIN)
                .mustChangePassword(true)
                .build();

        tenantUser = tenantUserRepository.save(tenantUser);
        saveDelivery(tenant, tenantUser, temporaryPassword, DeliveryStatus.PENDING, request.createdBy());
        return tenantUser;
    }

    private TenantUser updateExistingTenantUser(TenantUser tenantUser, ProvisionTenantUserRequest request) {
        tenantUser.setUsername(defaultUsername(request));
        tenantUser.setEmail(request.email());
        tenantUser.setFirstName(request.firstName());
        tenantUser.setLastName(request.lastName());
        tenantUser.setPrimaryRole(request.primaryRole());
        tenantUser.setRolesCsv(request.additionalRoles() != null ? String.join(",", request.additionalRoles()) : null);
        return tenantUserRepository.save(tenantUser);
    }

    private Optional<UserCredentialDelivery> findActiveDelivery(TenantUser tenantUser) {
        return deliveryRepository.findFirstByTenantUserIdAndStatusInOrderByCreatedAtDesc(
                tenantUser.getId(), ACTIVE_STATUSES
        ).filter(delivery -> delivery.getTempPasswordEnc() != null)
                .filter(delivery -> delivery.getExpiresAt().isAfter(OffsetDateTime.now()));
    }

    private UserCredentialDelivery issueFreshCredential(
            Tenant tenant, TenantUser tenantUser, boolean temporary, String actorSubject) {
        String password = PasswordGenerator.generate(14);
        keycloakRealmService.resetUserPassword(
                tenant.getKeycloakRealmName(),
                tenantUser.getKeycloakUserId(),
                password,
                temporary);

        tenantUser.setStatus(UserStatus.PENDING_FIRST_LOGIN);
        tenantUser.setMustChangePassword(temporary);
        tenantUserRepository.save(tenantUser);

        return saveDelivery(tenant, tenantUser, password, DeliveryStatus.PENDING, actorSubject);
    }

    private UserCredentialDelivery saveDelivery(
            Tenant tenant,
            TenantUser tenantUser,
            String plaintextPassword,
            DeliveryStatus status,
            String actorSubject) {

        UserCredentialDelivery delivery = UserCredentialDelivery.builder()
                .tenantId(tenant.getId())
                .tenantUserId(tenantUser.getId())
                .keycloakUserId(tenantUser.getKeycloakUserId())
                .username(tenantUser.getUsername())
                .tempPasswordEnc(encryptor.encrypt(plaintextPassword))
                .loginUrl(buildLoginUrl(tenant, tenantUser.getPrimaryRole()))
                .status(status)
                .expiresAt(OffsetDateTime.now().plusHours(ttlHours))
                .build();

        delivery = deliveryRepository.save(delivery);
        publishEvent(delivery.getId(), tenant.getId(), tenantUser.getId(),
                tenantUser.getKeycloakUserId(), null, status, actorSubject);
        return delivery;
    }

    private String decryptIfAvailable(UserCredentialDelivery delivery) {
        if (!ACTIVE_STATUSES.contains(delivery.getStatus())) {
            return null;
        }
        if (delivery.getTempPasswordEnc() == null || delivery.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return null;
        }
        try {
            return encryptor.decrypt(delivery.getTempPasswordEnc());
        } catch (Exception e) {
            log.warn("Could not decrypt credential delivery {}: {}", delivery.getId(), e.getMessage());
            return null;
        }
    }

    private void validateProvisionRequest(ProvisionTenantUserRequest request) {
        if (request.tenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (request.primaryRole() == null) {
            throw new IllegalArgumentException("primaryRole is required");
        }
        if (request.email() == null || request.email().isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
    }

    private String defaultUsername(ProvisionTenantUserRequest request) {
        return request.username() != null && !request.username().isBlank()
                ? request.username()
                : request.email();
    }

    private String buildDisplayName(String firstName, String lastName) {
        if (firstName == null || firstName.isBlank()) {
            return lastName == null || lastName.isBlank() ? "User" : lastName;
        }
        return lastName != null && !lastName.isBlank() ? firstName + " " + lastName : firstName;
    }

    private String buildLoginUrl(Tenant tenant, UserRole role) {
        String prefix = switch (role) {
            case ADMIN -> "admin-";
            case SUPERVISOR -> "supervisor-";
            case AGENT -> "agent-";
        };
        return "https://" + prefix + tenant.getSlug() + "." + baseDomain;
    }

    private void publishEvent(UUID deliveryId, UUID tenantId, UUID tenantUserId,
                              String keycloakUserId, DeliveryStatus previous,
                              DeliveryStatus current, String actorSubject) {
        eventPublisher.publishEvent(new CredentialDeliveryEvent(
                deliveryId, tenantId, tenantUserId, keycloakUserId,
                previous, current, actorSubject, Instant.now()));
    }
}
