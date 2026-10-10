package com.dalai.llama.tenant.onboarding;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.showcase.service.CreatorProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreatorOnboardingServiceTest {

    private CreatorEmailIdentityService emailIdentityService;
    private CreatorProfileService profileService;
    private TenantAppRepository tenantAppRepository;
    private CreatorOnboardingService onboarding;

    @BeforeEach
    void setUp() {
        emailIdentityService = mock(CreatorEmailIdentityService.class);
        profileService = mock(CreatorProfileService.class);
        tenantAppRepository = mock(TenantAppRepository.class);
        onboarding = new CreatorOnboardingService(emailIdentityService, profileService, tenantAppRepository);
    }

    @Test
    void createsBothTheEmailIdentityAndTheProfile() {
        UUID tenant = UUID.randomUUID();

        CreatorOnboardingService.OnboardingResult result = onboarding.onSubscriptionActivated(tenant, "Riya Motion");

        assertThat(result.emailIdentityReady()).isTrue();
        assertThat(result.publicProfileReady()).isTrue();
        verify(emailIdentityService).provisionForCreator(tenant, "Riya Motion");
        verify(profileService).ensureProfile(tenant, "Riya Motion");
    }

    @Test
    void aFailingEmailStepDoesNotStopTheProfileAndNeverThrows() {
        UUID tenant = UUID.randomUUID();
        when(emailIdentityService.provisionForCreator(tenant, "Riya")).thenThrow(new RuntimeException("db down"));

        CreatorOnboardingService.OnboardingResult result = onboarding.onSubscriptionActivated(tenant, "Riya");

        assertThat(result.emailIdentityReady()).isFalse();
        assertThat(result.publicProfileReady()).isTrue();
        verify(profileService).ensureProfile(tenant, "Riya");
    }

    @Test
    void onlyLiveTenantsWithAnAppAreEligibleForBackfill() {
        Tenant subscribed = tenant(TenantStatus.ACTIVE);
        Tenant registeredOnly = tenant(TenantStatus.CREATED);
        Tenant suspended = tenant(TenantStatus.SUSPENDED);
        when(tenantAppRepository.findFirstByTenantId(subscribed.getId())).thenReturn(Optional.of(new TenantApp()));
        when(tenantAppRepository.findFirstByTenantId(registeredOnly.getId())).thenReturn(Optional.empty());
        when(tenantAppRepository.findFirstByTenantId(suspended.getId())).thenReturn(Optional.of(new TenantApp()));

        assertThat(onboarding.isEligible(subscribed)).isTrue();
        assertThat(onboarding.isEligible(registeredOnly)).isFalse();
        assertThat(onboarding.isEligible(suspended)).isFalse();
    }

    private static Tenant tenant(TenantStatus status) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setStatus(status);
        return t;
    }
}
