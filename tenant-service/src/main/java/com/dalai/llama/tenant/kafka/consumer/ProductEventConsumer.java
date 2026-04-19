package com.dalai.llama.tenant.kafka.consumer;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.service.TenantAppService;
import com.dalai.llama.tenant.service.TenantService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
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
    private final TenantAppRepository tenantAppRepository;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @PostConstruct
    public void start() {
        new Thread(this::pollLoop, "product-event-consumer").start();
    }

    private void pollLoop() {
        KafkaConsumer<String, Map<String, Object>> consumer =
                new KafkaConsumer<>(Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                        ConsumerConfig.GROUP_ID_CONFIG, "tenant-product-consumer",
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        org.springframework.kafka.support.serializer.JsonDeserializer.class,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                        "spring.json.trusted.packages", "*"
                ));

        consumer.subscribe(List.of(
                "product.plan.assigned",
                "product.did.purchased",
                "product.subscription.activated"
        ));

        while (true) {
            for (ConsumerRecord<String, Map<String, Object>> record :
                    consumer.poll(Duration.ofSeconds(1))) {

                try {
                    Map<String, Object> payload = record.value();
                    UUID tenantId = UUID.fromString(payload.get("tenantId").toString());

                    switch (record.topic()) {
                        case "product.plan.assigned" -> handlePlanAssigned(tenantId, payload);
                        case "product.did.purchased" -> handleDidPurchased(tenantId, payload);
                        case "product.subscription.activated" -> handleSubscriptionActivated(tenantId, payload);
                    }
                } catch (Exception e) {
                    log.error("Error processing {} event: {}", record.topic(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Plan assigned to a tenant's app — update entitlements on TenantApp.
     */
    private void handlePlanAssigned(UUID tenantId, Map<String, Object> payload) {
        log.info("Plan assigned for tenant {}: planCode={}", tenantId, payload.get("planCode"));

        UUID subscriptionId = payload.containsKey("subscriptionId")
                ? UUID.fromString(payload.get("subscriptionId").toString()) : null;

        if (subscriptionId != null) {
            tenantAppRepository.findBySubscriptionId(subscriptionId).ifPresent(app -> {
                app.setPlanCode((String) payload.get("planCode"));
                app.setPlanTier((String) payload.get("planTier"));
                if (payload.containsKey("planId")) {
                    app.setPlanId(UUID.fromString(payload.get("planId").toString()));
                }
                tenantAppRepository.save(app);
                log.info("Updated plan on TenantApp {} for subscription {}", app.getId(), subscriptionId);
            });
        }
    }

    /**
     * DID purchased — stamp onto TenantApp.
     */
    private void handleDidPurchased(UUID tenantId, Map<String, Object> payload) {
        log.info("DID purchased for tenant {}: did={}", tenantId, payload.get("didNumber"));

        tenantAppRepository.findFirstByTenantId(tenantId).ifPresent(app -> {
            app.setDidNumber((String) payload.get("didNumber"));
            app.setDidCountry((String) payload.get("didCountry"));
            if (payload.containsKey("didId")) {
                app.setDidId(UUID.fromString(payload.get("didId").toString()));
            }
            tenantAppRepository.save(app);
            log.info("Stamped DID {} onto TenantApp {}", payload.get("didNumber"), app.getId());
        });
    }

    /**
     * Subscription activated — trigger provisioning if app is in PENDING state.
     * This is the Kafka-driven alternative to the REST endpoint.
     */
    private void handleSubscriptionActivated(UUID tenantId, Map<String, Object> payload) {
        log.info("Subscription activated for tenant {}", tenantId);

        UUID subscriptionId = payload.containsKey("subscriptionId")
                ? UUID.fromString(payload.get("subscriptionId").toString()) : null;

        if (subscriptionId != null) {
            tenantAppRepository.findBySubscriptionId(subscriptionId).ifPresent(app -> {
                if (app.getDeploymentStatus() == com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus.PENDING) {
                    log.info("Auto-triggering provisioning for TenantApp {} via Kafka event", app.getId());
                    tenantAppService.provisionApp(app.getId());
                }
            });
        }
    }
}