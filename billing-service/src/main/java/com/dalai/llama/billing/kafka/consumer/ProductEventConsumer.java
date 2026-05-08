package com.dalai.llama.billing.kafka.consumer;

import com.dalai.llama.billing.domain.event.SubscriptionActivationFailedEvent;
import com.dalai.llama.billing.service.PaymentService;
import com.dalai.llama.billing.service.UsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventConsumer {

    private final UsageService usageService;
    private final PaymentService paymentService;

    @KafkaListener(topics = "product.did.provisioned", groupId = "billing-service",
            containerFactory = "genericEventListenerFactory")
    public void onDidProvisioned(Object event) {
        log.info("Received product.did.provisioned: {}", event);
        usageService.trackProvisionedDid(event);
    }

    @KafkaListener(topics = "product.did.released", groupId = "billing-service",
            containerFactory = "genericEventListenerFactory")
    public void onDidReleased(Object event) {
        log.info("Received product.did.released: {}", event);
        usageService.untrackReleasedDid(event);
    }

    @KafkaListener(
            topics = "product.subscription.failed",
            groupId = "billing-service",
            containerFactory = "subscriptionFailedListenerFactory"
    )
    public void onSubscriptionFailed(SubscriptionActivationFailedEvent event) {
        log.warn("Received SubscriptionActivationFailedEvent — eventId={}, subscription={}, paymentId={}, failedAt={}, reason={}",
                event.getEventId(), event.getSubscriptionId(), event.getPaymentId(),
                event.getFailedAtStep(), event.getReason());

        if (event.getPaymentId() == null) {
            log.info("No paymentId on failed subscription {} — nothing to refund", event.getSubscriptionId());
            return;
        }

        try {
            String reason = "Subscription activation failed at " + event.getFailedAtStep()
                    + ": " + event.getReason();
            paymentService.refundSubscriptionPayment(
                    event.getTenantId(),
                    event.getPaymentId(),
                    event.getSubscriptionId(),
                    reason
            );
        } catch (Exception e) {
            log.error("Wallet refund failed for subscription={} payment={}: {}",
                    event.getSubscriptionId(), event.getPaymentId(), e.getMessage(), e);
        }
    }
}