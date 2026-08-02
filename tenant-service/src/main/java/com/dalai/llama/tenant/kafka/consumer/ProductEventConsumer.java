package com.dalai.llama.tenant.kafka.consumer;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.event.*;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.service.TenantAppService;
import com.dalai.llama.tenant.service.TenantService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventConsumer {

    private final TenantService tenantService;
    private final TenantAppService tenantAppService;

    @KafkaListener(topics = "product.plan.assigned", groupId = "tenant-product-consumer",
            containerFactory = "planAssignedListenerFactory")
    public void onPlanAssigned(PlanAssignedEvent event) {
        log.info("Received plan assignment event tenantId={}",
                event.getTenantId());

    }


    @KafkaListener(topics = "product.did.purchased", groupId = "tenant-product-consumer",
            containerFactory = "didPurchasedListenerFactory")
    public void onDidPurchased(DidPurchasedEvent event) {
        log.info("Received billing.wallet.credited: tenantId={}",
                event.getTenantId());

        // Push balance update to UI via WebSocket

    }

    @KafkaListener(topics = "product.subscription.activated", groupId = "tenant-product-consumer",
            containerFactory = "subscriptionActivatedListenerFactory")
    public void onSubscriptionActivated(SubscriptionActivatedEvent event) {
        log.info("Received subscription activated: tenantId={} subscriptionId={} event={}",
                event.getTenantId(), event.getSubscriptionId(),event);
        Tenant tenantData = tenantService.getTenantData(event.getTenantId());
        tenantAppService.handleSubscriptionActivated(event, tenantData);
    }

    @KafkaListener(topics = "product.subscription.failed", groupId = "tenant-product-consumer",
            containerFactory = "subscriptionActivationFailedListenerFactory")
    public void onSubscriptionActivationFailed(SubscriptionActivationFailedEvent event) {
        log.info("Received Subscription Failed: tenantId={} subscription Id={}",
                event.getTenantId(), event.getSubscriptionId());

        tenantAppService.handleSubscriptionActivationFailed(event);
    }

}
