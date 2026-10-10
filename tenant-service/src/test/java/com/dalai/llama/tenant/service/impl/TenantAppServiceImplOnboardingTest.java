package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.event.SubscriptionActivatedEvent;
import com.dalai.llama.tenant.onboarding.CreatorOnboardingService;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reproduces the redelivery gap: a subscription.activated redelivered after an earlier failure
 * finds the TenantApp already there and returns early. Onboarding (email identity + public
 * profile) must still run, and run before that check. */
@ExtendWith(MockitoExtension.class)
class TenantAppServiceImplOnboardingTest {

    @Mock private TenantAppRepository tenantAppRepository;
    @Mock private CreatorOnboardingService creatorOnboardingService;
    @InjectMocks private TenantAppServiceImpl service;

    @Test
    void redeliveryWithAnExistingAppStillOnboardsTheCreator() {
        UUID tenantId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        SubscriptionActivatedEvent event = new SubscriptionActivatedEvent();
        event.setTenantId(tenantId);
        event.setSubscriptionId(subscriptionId);
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Riya Motion");
        TenantApp existing = new TenantApp();
        existing.setDeploymentStatus(ProvisioningTaskStatus.FAILED);
        when(tenantAppRepository.findBySubscriptionId(subscriptionId)).thenReturn(Optional.of(existing));

        service.handleSubscriptionActivated(event, tenant);

        InOrder order = inOrder(creatorOnboardingService, tenantAppRepository);
        order.verify(creatorOnboardingService).onSubscriptionActivated(tenantId, "Riya Motion");
        order.verify(tenantAppRepository).findBySubscriptionId(subscriptionId);
        verify(creatorOnboardingService).onSubscriptionActivated(tenantId, "Riya Motion");
    }
}
