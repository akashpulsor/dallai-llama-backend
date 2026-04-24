package com.dalai.llama.product.kafka.producer;


import com.dalai.llama.product.domain.event.*;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public static final String PLAN_ASSIGNED_TOPIC = "product.plan.assigned";
    public static final String PLAN_CHANGED_TOPIC = "product.plan.changed";
    public static final String DID_PURCHASED_TOPIC = "product.did.purchased";
    public static final String DID_PROVISIONED_TOPIC = "product.did.provisioned";
    public static final String DID_RELEASED_TOPIC = "product.did.released";

    public static final String SUBSCRIPTION_ACTIVATED_TOPIC = "product.subscription.activated";
    public static final String SUBSCRIPTION_FAILED_TOPIC = "product.subscription.failed";

    public void publishPlanAssigned(PlanAssignedEvent event) {
        kafkaTemplate.send(PLAN_ASSIGNED_TOPIC, event.getTenantId().toString(), event);
    }

    public void publishPlanChanged(PlanChangedEvent event) {
        kafkaTemplate.send(PLAN_CHANGED_TOPIC, event.getTenantId().toString(), event);
    }

    public void publishDidPurchased(DidPurchasedEvent event) {
        kafkaTemplate.send(DID_PURCHASED_TOPIC, event.getTenantId().toString(), event);
    }

    public void publishDidProvisioned(DidProvisionedEvent event) {
        kafkaTemplate.send(DID_PROVISIONED_TOPIC, event.getTenantId().toString(), event);
    }

    public void publishDidReleased(DidReleasedEvent event) {
        kafkaTemplate.send(DID_RELEASED_TOPIC, event.getTenantId().toString(), event);
    }

    public void publishSubscriptionActivated(SubscriptionActivatedEvent event) {
        kafkaTemplate.send(
                SUBSCRIPTION_ACTIVATED_TOPIC,
                event.getTenantId().toString(),
                event
        );
    }

    public void publishSubscriptionFailed(SubscriptionActivationFailedEvent event) {
        kafkaTemplate.send(
                SUBSCRIPTION_FAILED_TOPIC,
                event.getTenantId().toString(),
                event
        );
    }
}
