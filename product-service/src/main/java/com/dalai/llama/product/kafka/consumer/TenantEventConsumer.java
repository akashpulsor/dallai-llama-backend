package com.dalai.llama.product.kafka.consumer;

import com.dalai.llama.product.domain.entity.Did;
import com.dalai.llama.product.domain.entity.Plan;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import com.dalai.llama.product.repository.DidRepository;
import com.dalai.llama.product.repository.PlanRepository;
import com.dalai.llama.product.service.DidService;
import com.dalai.llama.product.service.EntitlementService;
import com.dalai.llama.product.service.PlanAssignmentService;
import com.dalai.llama.product.service.impl.SubscriptionService;
import com.dalai.llama.tenant.domain.event.ProvisioningCompletedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantEventConsumer {

    private final PlanAssignmentService planAssignmentService;
    private final PlanRepository planRepository;
    private final DidRepository didRepository;
    private final DidService didService;
    private final EntitlementService entitlementService;
    private final ObjectMapper objectMapper;
    private final SubscriptionService subscriptionService;

    @KafkaListener(topics = "tenant.created", groupId = "product-service")
    public void onTenantCreated(String message) {
        log.info("Received tenant.created event: {}", message);

        try {
            JsonNode json = objectMapper.readTree(message);
            UUID tenantId = UUID.fromString(json.get("tenantId").asText());
            String productCode = json.has("productCode") ? json.get("productCode").asText() : "AI_CC";

            // Assign default plan for the product
            Plan defaultPlan = planRepository.findByProduct_CodeAndIsDefaultTrue(productCode)
                    .orElse(null);

            if (defaultPlan != null) {
                planAssignmentService.assignPlan(tenantId, defaultPlan.getId());
                log.info("Assigned default plan {} to tenant {}", defaultPlan.getCode(), tenantId);
            } else {
                log.warn("No default plan found for product {}", productCode);
            }

        } catch (Exception e) {
            log.error("Failed to process tenant.created event: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "tenant.deleted", groupId = "product-service")
    public void onTenantDeleted(String message) {
        log.info("Received tenant.deleted event: {}", message);

        try {
            JsonNode json = objectMapper.readTree(message);
            UUID tenantId = UUID.fromString(json.get("tenantId").asText());

            // 1. Release all tenant DIDs
            List<Did> dids = didRepository.findByTenantId(tenantId);
            for (Did did : dids) {
                if (did.getStatus() != DidStatus.RELEASED) {
                    try {
                        didService.releaseDid(tenantId, did.getId());
                        log.info("Released DID {} for deleted tenant {}", did.getNumber(), tenantId);
                    } catch (Exception e) {
                        log.error("Failed to release DID {}: {}", did.getNumber(), e.getMessage());
                    }
                }
            }

            // 2. Remove plan assignment
            planAssignmentService.removePlan(tenantId);

            // 3. Invalidate entitlement cache
            entitlementService.invalidateCache(tenantId);

            log.info("Cleaned up product resources for deleted tenant {}", tenantId);

        } catch (Exception e) {
            log.error("Failed to process tenant.deleted event: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "tenant.suspended", groupId = "product-service")
    public void onTenantSuspended(String message) {
        log.info("Received tenant.suspended event: {}", message);

        try {
            JsonNode json = objectMapper.readTree(message);
            UUID tenantId = UUID.fromString(json.get("tenantId").asText());

            // Suspend all tenant DIDs
            List<Did> dids = didRepository.findByTenantId(tenantId);
            for (Did did : dids) {
                if (did.getStatus() == DidStatus.ACTIVE) {
                    did.setStatus(DidStatus.SUSPENDED);
                    didRepository.save(did);
                    log.info("Suspended DID {} for tenant {}", did.getNumber(), tenantId);
                }
            }

        } catch (Exception e) {
            log.error("Failed to process tenant.suspended event: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "tenant.activated", groupId = "product-service")
    public void onTenantActivated(String message) {
        log.info("Received tenant.activated event: {}", message);

        try {
            JsonNode json = objectMapper.readTree(message);
            UUID tenantId = UUID.fromString(json.get("tenantId").asText());

            // Reactivate suspended DIDs
            List<Did> dids = didRepository.findByTenantId(tenantId);
            for (Did did : dids) {
                if (did.getStatus() == DidStatus.SUSPENDED) {
                    did.setStatus(DidStatus.ACTIVE);
                    didRepository.save(did);
                    log.info("Reactivated DID {} for tenant {}", did.getNumber(), tenantId);
                }
            }

        } catch (Exception e) {
            log.error("Failed to process tenant.activated event: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "tenant.provisioning.completed", groupId = "product-service",
            containerFactory = "provisioningCompletedListenerFactory")
    public void onProvisioningCompleted(ProvisioningCompletedEvent event) {
        log.info("Received provisioning completed: subscriptionId={} status={}",
                event.getSubscriptionId(), event.getStatus());

        if ("COMPLETED".equals(event.getStatus())) {
            subscriptionService.markProvisioned(event.getSubscriptionId(), event.getTenantAppId());
        } else {
            subscriptionService.markProvisioningFailed(event.getSubscriptionId(), event.getFailureReason());
        }
    }
}