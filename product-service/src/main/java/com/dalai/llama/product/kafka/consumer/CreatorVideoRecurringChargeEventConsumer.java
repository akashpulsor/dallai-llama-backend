package com.dalai.llama.product.kafka.consumer;

import com.dalai.llama.product.domain.event.RecurringChargeOutcomeEvent;
import com.dalai.llama.product.service.impl.CreatorVideoSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to billing-service's per-charge renewal outcome for creator-video subscriptions --
 * "SUBSCRIPTION" is the only recurring-charge type this product creates (see
 * {@code BillingServiceClient.RecurringChargeRequest#subscriptionFee}), so every event landing
 * here that matches a known subscriptionId belongs to this product; anything else is silently
 * ignored by {@link CreatorVideoSubscriptionService#handleRenewalOutcome}.
 *
 * <p>On failure this only flips the subscription to PAST_DUE (which the entitlement resolver
 * already treats as free-tier) -- it does NOT send a push notification itself. This is the
 * intended extensibility point for that (a notification service would subscribe to the same
 * outcome, or a downstream event this could publish), but no push-delivery channel (FCM/APNs/web
 * push) exists in this codebase yet to wire it to; building one is a separate infra project.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreatorVideoRecurringChargeEventConsumer {

    private final CreatorVideoSubscriptionService subscriptionService;

    @KafkaListener(
            topics = "billing.recurring-charge.outcome",
            groupId = "product-service",
            containerFactory = "recurringChargeOutcomeListenerFactory"
    )
    public void onRecurringChargeOutcome(RecurringChargeOutcomeEvent event) {
        log.info("Received RecurringChargeOutcomeEvent subscriptionId={} succeeded={} chargeType={}",
                event.getSubscriptionId(), event.isSucceeded(), event.getChargeType());
        subscriptionService.handleRenewalOutcome(event.getSubscriptionId(), event.isSucceeded());
    }
}
