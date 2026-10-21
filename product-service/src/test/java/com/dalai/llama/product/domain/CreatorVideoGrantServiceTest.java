package com.dalai.llama.product.domain;

import com.dalai.llama.product.client.BillingServiceClient;
import com.dalai.llama.product.domain.entity.Plan;
import com.dalai.llama.product.domain.entity.Product;
import com.dalai.llama.product.domain.entity.Subscription;
import com.dalai.llama.product.domain.entity.enums.BillingCycle;
import com.dalai.llama.product.domain.entity.enums.PlanTier;
import com.dalai.llama.product.domain.entity.enums.SubscriptionStatus;
import com.dalai.llama.product.repository.PlanRepository;
import com.dalai.llama.product.repository.ProductRepository;
import com.dalai.llama.product.repository.SubscriptionRepository;
import com.dalai.llama.product.service.CreatorVideoEntitlementService;
import com.dalai.llama.product.service.impl.CreatorVideoGrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ops give a creator Studio or Pro free for some days: no charge, no renewal, it ends by itself. */
class CreatorVideoGrantServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private final UUID tenant = UUID.randomUUID();
    private final SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final BillingServiceClient billing = mock(BillingServiceClient.class);
    private final CreatorVideoEntitlementService entitlements = mock(CreatorVideoEntitlementService.class);
    private final CreatorVideoGrantService grants = new CreatorVideoGrantService(subscriptions, products, plans, billing, entitlements,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final Product product = Product.builder().id(UUID.randomUUID()).code("CREATOR_VIDEO").build();
    private final Plan pro = plan("CREATOR_VIDEO_PRO_MONTHLY", PlanTier.PROFESSIONAL);
    private final Plan free = plan("CREATOR_VIDEO_FREE", PlanTier.FREE);

    @BeforeEach
    void catalogue() {
        when(products.findByCode("CREATOR_VIDEO")).thenReturn(Optional.of(product));
        when(plans.findByCode(pro.getCode())).thenReturn(Optional.of(pro));
        when(plans.findByCode(free.getCode())).thenReturn(Optional.of(free));
        when(subscriptions.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void aGrantIsTheFullPlanFreeUntilItsEnd_neverChargedOrRenewed() {
        when(subscriptions.findFirstByTenantIdAndProductId(tenant, product.getId())).thenReturn(Optional.empty());

        var view = grants.grant(tenant, pro.getCode(), 30, " Launch partner ");

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.complimentary()).isTrue();
        assertThat(view.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(view.note()).isEqualTo("Launch partner");
        verify(billing, never()).chargeProductSubscription(any(), any(), any(), any());
        verify(billing, never()).createRecurringCharge(any(), any());
        verify(entitlements).invalidateCache(any(), any());
    }

    @Test
    void entitlementsStopWhenTheGrantEnds() {
        Subscription granted = Subscription.builder().tenantId(tenant).product(product).plan(pro).status(SubscriptionStatus.PENDING_PAYMENT).build();
        granted.grantFree(NOW.plus(Duration.ofDays(7)), null);

        assertThat(granted.entitledAt(NOW.plus(Duration.ofDays(6)))).isTrue();
        assertThat(granted.entitledAt(NOW.plus(Duration.ofDays(8)))).isFalse();
    }

    @Test
    void aPayingCreatorIsLeftAlone_andTheFreePlanCantBeGranted() {
        Subscription paid = Subscription.builder().id(UUID.randomUUID()).tenantId(tenant).product(product).plan(pro).build();
        paid.activate();
        when(subscriptions.findFirstByTenantIdAndProductId(tenant, product.getId())).thenReturn(Optional.of(paid));

        assertThatThrownBy(() -> grants.grant(tenant, pro.getCode(), 30, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> grants.grant(tenant, free.getCode(), 30, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grants.grant(tenant, pro.getCode(), 0, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLapsedPaidSubscriptionBecomesTheGrant_withItsRenewalsCancelled() {
        Subscription lapsed = Subscription.builder().id(UUID.randomUUID()).tenantId(tenant).product(product).plan(pro)
                .status(SubscriptionStatus.PAST_DUE).billingCycle(BillingCycle.MONTHLY).build();
        when(subscriptions.findFirstByTenantIdAndProductId(tenant, product.getId())).thenReturn(Optional.of(lapsed));

        var view = grants.grant(tenant, pro.getCode(), 90, null);

        verify(billing).cancelRecurringCharges(tenant, lapsed.getId());
        assertThat(view.subscriptionId()).isEqualTo(lapsed.getId());
        assertThat(view.complimentary()).isTrue();
        assertThat(lapsed.getBillingCycle()).isNull();
    }

    @Test
    void endedGrantsAreClosed_andOpsCanEndOneEarly() {
        Subscription over = Subscription.builder().id(UUID.randomUUID()).tenantId(tenant).product(product).plan(pro).build();
        over.grantFree(NOW.minus(Duration.ofHours(1)), null);
        when(subscriptions.findExpiringSoon(NOW)).thenReturn(List.of(over));

        grants.expireEnded();
        assertThat(over.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);

        Subscription running = Subscription.builder().id(UUID.randomUUID()).tenantId(tenant).product(product).plan(pro).build();
        running.grantFree(NOW.plus(Duration.ofDays(10)), null);
        when(subscriptions.findById(running.getId())).thenReturn(Optional.of(running));
        assertThat(grants.end(running.getId()).status()).isEqualTo("EXPIRED");
    }

    private Plan plan(String code, PlanTier tier) {
        return Plan.builder().id(UUID.randomUUID()).code(code).name(code).tier(tier).product(product)
                .billingCycle(BillingCycle.MONTHLY).build();
    }
}
