package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.client.BillingServiceClient;
import com.dalai.llama.product.domain.entity.Plan;
import com.dalai.llama.product.domain.entity.Product;
import com.dalai.llama.product.domain.entity.Subscription;
import com.dalai.llama.product.domain.entity.enums.PlanTier;
import com.dalai.llama.product.domain.entity.enums.SubscriptionStatus;
import com.dalai.llama.product.domain.exception.PlanNotFoundException;
import com.dalai.llama.product.domain.exception.ProductNotFoundException;
import com.dalai.llama.product.domain.exception.SubscriptionNotFoundException;
import com.dalai.llama.product.repository.PlanRepository;
import com.dalai.llama.product.repository.ProductRepository;
import com.dalai.llama.product.repository.SubscriptionRepository;
import com.dalai.llama.product.service.CreatorVideoEntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ops gives a creator a paid creator-video plan (Studio or Pro) free for a number of days: a
 * complimentary subscription, never charged or renewed, that ends by itself. A creator already on a
 * paid plan isn't touched; a later grant replaces an earlier one (extend or change plan).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorVideoGrantService {

    static final String PRODUCT_CODE = "CREATOR_VIDEO";
    static final int MAX_DAYS = 3660;

    private final SubscriptionRepository subscriptions;
    private final ProductRepository products;
    private final PlanRepository plans;
    private final BillingServiceClient billing;
    private final CreatorVideoEntitlementService entitlements;
    private final Clock clock;

    /** A creator's creator-video subscription as ops see it; {@code subscriptionId} null when none. */
    public record GrantView(UUID tenantId, UUID subscriptionId, String planCode, String planName, String status,
                            boolean complimentary, Instant expiresAt, String note) {
    }

    /** The paid plans ops can grant: one per tier (its monthly row; a grant has no billing cycle). */
    public List<Plan> grantablePlans() {
        return plans.findByProduct_CodeAndActiveTrue(PRODUCT_CODE).stream()
                .filter(p -> p.getTier() != PlanTier.FREE && p.getBillingCycle() != null && p.getBillingCycle().name().equals("MONTHLY"))
                .toList();
    }

    public GrantView view(UUID tenantId) {
        return current(tenantId).map(s -> toView(s, clock.instant()))
                .orElse(new GrantView(tenantId, null, null, null, "UNSUBSCRIBED", false, null, null));
    }

    @Transactional
    public GrantView grant(UUID tenantId, String planCode, int days, String note) {
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("Days must be between 1 and " + MAX_DAYS);
        Product product = products.findByCode(PRODUCT_CODE).orElseThrow(() -> new ProductNotFoundException(PRODUCT_CODE));
        Plan plan = plans.findByCode(planCode).orElseThrow(() -> new PlanNotFoundException(planCode));
        if (!plan.getProduct().getId().equals(product.getId()) || plan.getTier() == PlanTier.FREE) {
            throw new IllegalArgumentException("Grant a paid creator-video plan (Studio or Pro), not " + planCode);
        }
        Instant now = clock.instant();
        Subscription subscription = current(tenantId).orElse(null);
        if (subscription != null && !subscription.isComplimentary() && subscription.getStatus() == SubscriptionStatus.ACTIVE) {
            throw new IllegalStateException("This creator pays for " + subscription.getPlan().getName()
                    + "; cancel that first if they should be on a free grant instead");
        }
        if (subscription == null) {
            subscription = Subscription.builder().tenantId(tenantId).product(product).plan(plan)
                    .status(SubscriptionStatus.PENDING_PAYMENT).agentSeats(0).build();
        } else if (!subscription.isComplimentary()) {
            // A lapsed or paused paid subscription becomes the grant: its renewal must not charge.
            try {
                billing.cancelRecurringCharges(tenantId, subscription.getId());
            } catch (Exception e) {
                log.warn("Could not cancel renewals of {} before a free grant (continuing): {}", subscription.getId(), e.getMessage());
            }
        }
        subscription.setPlan(plan);
        subscription.setBillingCycle(null);
        subscription.grantFree(now.plus(Duration.ofDays(days)), blankToNull(note));
        subscription = subscriptions.save(subscription);
        entitlements.invalidateCache(tenantId, subscription.getId());
        log.info("Ops granted {} free to tenant {} for {} days (subscription {})", plan.getCode(), tenantId, days, subscription.getId());
        return toView(subscription, now);
    }

    /** Ends a complimentary grant now; the creator is back on the free plan. */
    @Transactional
    public GrantView end(UUID subscriptionId) {
        Subscription subscription = subscriptions.findById(subscriptionId).orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));
        if (!subscription.isComplimentary()) throw new IllegalStateException("Only a free grant can be ended here");
        expire(subscription);
        return toView(subscription, clock.instant());
    }

    /** Grants past their end are closed (entitlements already stopped at the end; this tidies the status). */
    @Scheduled(fixedDelayString = "${creator-video.grants.expire-delay-ms:3600000}", initialDelayString = "${creator-video.grants.expire-initial-delay-ms:120000}")
    @Transactional
    public void expireEnded() {
        Instant now = clock.instant();
        for (Subscription s : subscriptions.findExpiringSoon(now)) {
            if (s.isComplimentary()) expire(s);
        }
    }

    private void expire(Subscription subscription) {
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        subscriptions.save(subscription);
        entitlements.invalidateCache(subscription.getTenantId(), subscription.getId());
        log.info("Free grant {} for tenant {} ended", subscription.getId(), subscription.getTenantId());
    }

    private Optional<Subscription> current(UUID tenantId) {
        return products.findByCode(PRODUCT_CODE).flatMap(p -> subscriptions.findFirstByTenantIdAndProductId(tenantId, p.getId()));
    }

    private static GrantView toView(Subscription s, Instant now) {
        String status = s.isComplimentary() && s.getStatus() == SubscriptionStatus.ACTIVE && !s.entitledAt(now) ? "EXPIRED" : s.getStatus().name();
        return new GrantView(s.getTenantId(), s.getId(), s.getPlan().getCode(), s.getPlan().getName(), status, s.isComplimentary(),
                s.getExpiresAt(), s.getGrantNote());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
