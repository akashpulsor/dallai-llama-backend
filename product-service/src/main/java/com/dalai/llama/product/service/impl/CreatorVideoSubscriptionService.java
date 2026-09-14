package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.client.BillingServiceClient;
import com.dalai.llama.product.client.BillingServiceClient.RecurringChargeRequest;
import com.dalai.llama.product.domain.entity.Plan;
import com.dalai.llama.product.domain.entity.Product;
import com.dalai.llama.product.domain.entity.Subscription;
import com.dalai.llama.product.domain.entity.enums.SubscriptionStatus;
import com.dalai.llama.product.domain.exception.PlanNotFoundException;
import com.dalai.llama.product.domain.exception.ProductNotFoundException;
import com.dalai.llama.product.domain.exception.SubscriptionNotFoundException;
import com.dalai.llama.product.dto.creatorvideo.CreatorVideoSubscribeRequest;
import com.dalai.llama.product.dto.creatorvideo.CreatorVideoSubscriptionResponse;
import com.dalai.llama.product.repository.PlanRepository;
import com.dalai.llama.product.repository.ProductRepository;
import com.dalai.llama.product.repository.SubscriptionRepository;
import com.dalai.llama.product.service.CreatorVideoEntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Subscribe/cancel/pause/resume for the creator-video product. Deliberately does NOT go through
 * {@link SubscriptionService}'s saga machinery -- that saga unconditionally provisions DID/SIP/
 * PSTN-channel/tenant-trunk telephony resources ({@code subscribe()} dereferences
 * {@code request.getDid().getNumber()} on its very first line), none of which a video subscription
 * needs. Reuses the same {@code products}/{@code plans}/{@code subscriptions} tables (this is just
 * another {@code Product} row), but subscribe here is a single synchronous step -- charge the
 * wallet, activate, schedule the renewal -- since there's no multi-resource provisioning to
 * orchestrate or compensate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorVideoSubscriptionService {

    private static final String PRODUCT_CODE = "CREATOR_VIDEO";

    private final SubscriptionRepository subscriptionRepository;
    private final ProductRepository productRepository;
    private final PlanRepository planRepository;
    private final BillingServiceClient billingClient;
    private final CreatorVideoEntitlementService entitlementService;

    @Transactional
    public CreatorVideoSubscriptionResponse subscribe(CreatorVideoSubscribeRequest request) {
        UUID tenantId = request.getTenantId();
        Product product = productRepository.findByCode(PRODUCT_CODE)
                .orElseThrow(() -> new ProductNotFoundException(PRODUCT_CODE));
        Plan plan = planRepository.findByCode(request.getPlanCode())
                .orElseThrow(() -> new PlanNotFoundException(request.getPlanCode()));
        if (!plan.getProduct().getId().equals(product.getId())) {
            throw new IllegalArgumentException("Plan " + request.getPlanCode() + " does not belong to " + PRODUCT_CODE);
        }

        Subscription subscription = subscriptionRepository
                .findFirstByTenantIdAndProductId(tenantId, product.getId())
                .orElse(null);

        if (subscription != null) {
            if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
                throw new IllegalStateException("Tenant " + tenantId + " already has an active creator-video subscription");
            }
            if (subscription.getStatus() == SubscriptionStatus.PAUSED) {
                throw new IllegalStateException("Subscription " + subscription.getId() + " is paused -- resume it instead of subscribing again");
            }
        }

        BillingServiceClient.WalletBalanceResponse balance = billingClient.getCurrentBalance(tenantId);
        if (balance.balance().compareTo(plan.getMonthlyPrice()) < 0) {
            // Create the Razorpay order here rather than answering "you are short" and leaving the
            // caller to work out how much, create its own order, and retry. Those were three
            // steps a client could get wrong, and did: the browser had to parse a shortfall out
            // of an error body, round it, top the wallet up and subscribe again, and any break in
            // that chain looked to the creator like a subscribe button that simply refused.
            BigDecimal shortfall = plan.getMonthlyPrice().subtract(balance.balance());
            try {
                BillingServiceClient.SubscriptionPaymentResponse payment = billingClient.createSubscriptionPayment(
                        tenantId, plan.getCode(), plan.getMonthlyPrice(), balance.balance(),
                        subscription == null ? null : subscription.getId());
                return CreatorVideoSubscriptionResponse.builder()
                        .status("PAYMENT_REQUIRED")
                        .planCode(plan.getCode())
                        .planName(plan.getName())
                        .price(plan.getMonthlyPrice())
                        .currency(payment.currency() == null ? balance.currency() : payment.currency())
                        .currentWalletBalance(balance.balance())
                        .shortFallAmount(shortfall)
                        .paymentId(payment.paymentId())
                        .gatewayOrderId(payment.gatewayOrderId())
                        .razorpayKeyId(payment.razorpayKeyId())
                        .amountDue(payment.totalAmount())
                        .build();
            } catch (RuntimeException ex) {
                // Razorpay or billing is down. Fall back to the old shape so the creator is told
                // they are short rather than shown a generic failure -- still actionable by
                // recharging the wallet by hand.
                log.warn("Could not create subscription payment order tenantId={} planCode={}: {}",
                        tenantId, plan.getCode(), ex.getMessage());
                return CreatorVideoSubscriptionResponse.builder()
                        .status("INSUFFICIENT_BALANCE")
                        .planCode(plan.getCode())
                        .planName(plan.getName())
                        .price(plan.getMonthlyPrice())
                        .currency(balance.currency())
                        .currentWalletBalance(balance.balance())
                        .shortFallAmount(shortfall)
                        .build();
            }
        }

        // Reuse the same row across a cancel -> resubscribe or a self-healed past-due recovery,
        // rather than accumulating a new subscription row per attempt -- only a genuinely first-
        // time subscriber gets a brand new one.
        if (subscription == null) {
            subscription = Subscription.builder()
                    .tenantId(tenantId)
                    .product(product)
                    .plan(plan)
                    .status(SubscriptionStatus.PENDING_PAYMENT)
                    .agentSeats(0)
                    .build();
        } else {
            subscription.setPlan(plan);
        }
        subscription.setBillingCycle(plan.getBillingCycle());
        subscription = subscriptionRepository.save(subscription);

        billingClient.chargeProductSubscription(tenantId, subscription.getId(), plan.getMonthlyPrice(),
                "Creator Video - " + plan.getName());

        subscription.activate();
        subscription = subscriptionRepository.save(subscription);

        billingClient.createRecurringCharge(tenantId, RecurringChargeRequest.subscriptionFee(
                plan.getMonthlyPrice(),
                plan.getBillingCycle() != null ? plan.getBillingCycle().name() : "MONTHLY",
                subscription.getId(),
                "Creator Video - " + plan.getName()));

        entitlementService.invalidateCache(tenantId, subscription.getId());

        log.info("Creator-video subscription {} activated for tenant {} plan {}", subscription.getId(), tenantId, plan.getCode());

        return toResponse(subscription, plan);
    }

    @Transactional
    public void cancel(UUID subscriptionId) {
        Subscription subscription = requireSubscription(subscriptionId);
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
            log.info("Creator-video subscription {} already cancelled", subscriptionId);
            return;
        }
        UUID tenantId = subscription.getTenantId();
        try {
            billingClient.cancelRecurringCharges(tenantId, subscriptionId);
        } catch (Exception e) {
            log.warn("Failed to cancel recurring charges for {} (continuing): {}", subscriptionId, e.getMessage());
        }
        subscription.cancel();
        subscriptionRepository.save(subscription);
        entitlementService.invalidateCache(tenantId, subscriptionId);
        log.info("Creator-video subscription {} cancelled for tenant {}", subscriptionId, tenantId);
    }

    @Transactional
    public void pause(UUID subscriptionId) {
        Subscription subscription = requireSubscription(subscriptionId);
        subscription.pause(); // throws IllegalStateException if not currently ACTIVE
        subscriptionRepository.save(subscription);
        billingClient.pauseRecurringCharges(subscription.getTenantId(), subscriptionId);
        entitlementService.invalidateCache(subscription.getTenantId(), subscriptionId);
        log.info("Creator-video subscription {} paused for tenant {}", subscriptionId, subscription.getTenantId());
    }

    @Transactional
    public void resume(UUID subscriptionId) {
        Subscription subscription = requireSubscription(subscriptionId);
        subscription.resume(); // throws IllegalStateException if not currently PAUSED; sets a fresh expiresAt
        subscriptionRepository.save(subscription);
        LocalDate nextChargeDate = subscription.getExpiresAt().atZone(ZoneOffset.UTC).toLocalDate();
        billingClient.resumeRecurringCharges(subscription.getTenantId(), subscriptionId, nextChargeDate);
        entitlementService.invalidateCache(subscription.getTenantId(), subscriptionId);
        log.info("Creator-video subscription {} resumed for tenant {}", subscriptionId, subscription.getTenantId());
    }

    /** Reacts to billing-service's renewal outcome for this subscription's recurring charge --
     * see {@code CreatorVideoRecurringChargeEventConsumer}, the actual Kafka entry point. Failure
     * drops straight to PAST_DUE (no grace period, per product decision); success on a later
     * retry re-activates automatically. */
    @Transactional
    public void handleRenewalOutcome(UUID subscriptionId, boolean succeeded) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (subscription == null) {
            log.warn("Renewal outcome for unknown subscription {} -- ignoring", subscriptionId);
            return;
        }
        if (succeeded) {
            if (subscription.getStatus() == SubscriptionStatus.PAST_DUE) {
                subscription.activate();
                subscriptionRepository.save(subscription);
                entitlementService.invalidateCache(subscription.getTenantId(), subscriptionId);
                log.info("Creator-video subscription {} recovered from PAST_DUE on successful renewal", subscriptionId);
            }
            return;
        }
        if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
            subscription.markPastDue();
            subscriptionRepository.save(subscription);
            entitlementService.invalidateCache(subscription.getTenantId(), subscriptionId);
            log.warn("Creator-video subscription {} marked PAST_DUE -- renewal charge failed", subscriptionId);
            // Actual push-notification delivery is a separate infra concern this event only
            // signals into -- see CreatorVideoRecurringChargeEventConsumer's javadoc.
        }
    }

    private Subscription requireSubscription(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));
    }

    private CreatorVideoSubscriptionResponse toResponse(Subscription subscription, Plan plan) {
        return CreatorVideoSubscriptionResponse.builder()
                .subscriptionId(subscription.getId())
                .status(subscription.getStatus().name())
                .planCode(plan.getCode())
                .planName(plan.getName())
                .billingCycle(plan.getBillingCycle() != null ? plan.getBillingCycle().name() : null)
                .price(plan.getMonthlyPrice())
                .currency(plan.getCurrency())
                .currentPeriodEnd(subscription.getExpiresAt())
                .build();
    }
}
