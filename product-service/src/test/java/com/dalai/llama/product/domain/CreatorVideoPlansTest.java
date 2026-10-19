package com.dalai.llama.product.domain;

import com.dalai.llama.product.domain.entity.CreatorVideoPlanEntitlement;
import com.dalai.llama.product.domain.entity.Subscription;
import com.dalai.llama.product.domain.entity.enums.BillingCycle;
import com.dalai.llama.product.dto.creatorvideo.CreatorVideoEntitlements;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The outreach caps travel with the entitlements, and a 6-month plan renews every 6 months. */
class CreatorVideoPlansTest {

    @Test
    void entitlementsCarryTheOutreachCaps_nullReachMeaningEveryBrand() {
        CreatorVideoPlanEntitlement pro = CreatorVideoPlanEntitlement.builder().editsEnabled(true).monthlyOutreachEmails(3000).brandReach(null).build();

        CreatorVideoEntitlements view = CreatorVideoEntitlements.of(pro);

        assertThat(view.editsEnabled()).isTrue();
        assertThat(view.monthlyOutreachEmails()).isEqualTo(3000);
        assertThat(view.brandReach()).isNull();
    }

    @Test
    void theFreeFallbackIs100EmailsAMonthToTenBrands() {
        assertThat(CreatorVideoEntitlements.freeDefaults().monthlyOutreachEmails()).isEqualTo(100);
        assertThat(CreatorVideoEntitlements.freeDefaults().brandReach()).isEqualTo(10);
        // A row saved without the caps set gets the free plan's.
        assertThat(CreatorVideoPlanEntitlement.builder().build().getMonthlyOutreachEmails()).isEqualTo(100);
    }

    @Test
    void aSixMonthPlanRunsHalfAYear() {
        Subscription subscription = Subscription.builder().billingCycle(BillingCycle.HALF_YEARLY).build();

        subscription.activate();

        assertThat(Duration.between(Instant.now(), subscription.getExpiresAt()).toDays()).isCloseTo(182L, within(1L));
    }
}
