package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.dto.request.SubscriptionActiveRequest;
import com.dalai.llama.tenant.dto.response.TenantProvisioningConfigResponse;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.service.impl.AgentProvisionService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/tenants")
@Slf4j
@Hidden
public class InternalTenantController {

    private final TenantService tenantService;
    private final AgentProvisionService agentProvisionService;

    @PostMapping("/{id}/plan-assigned")
    public void planAssigned(
            @PathVariable UUID id,
            @RequestParam UUID planId,
            @RequestParam String planCode) {
        tenantService.onPlanAssigned(id, planId, planCode);
    }

    @PostMapping("/{id}/identity")
    public void setIdentity(
            @PathVariable UUID id) {
        tenantService.setIdentity(id);
    }

    @PostMapping("/{id}/did-purchased")
    public void didPurchased(@PathVariable UUID id) {
        tenantService.onDidPurchased(id);
    }

    @PostMapping("/{id}/wallet-created")
    public void walletCreated(
            @PathVariable UUID id,
            @RequestParam UUID walletId) {
        tenantService.onWalletCreated(id, walletId);
    }

    /**
     * POST /api/v1/internal/tenants/{tenantId}/subscription-active
     *
     * Called by product-service when subscription becomes ACTIVE.
     * Triggers:
     * 1. Remove tenant expiry (make permanent)
     * 2. Setup Keycloak (tenant_id attribute, roles)
     * 3. Update tenant status to ACTIVE
     */
    @PostMapping("/{tenantId}/activate")
    @Operation(summary = "Notify subscription active")
    public ResponseEntity<Void> onSubscriptionActive(
            @PathVariable UUID tenantId,
            @RequestBody SubscriptionActiveRequest request) {

        log.info("Subscription active for tenant {} - product: {}", tenantId, request.productCode());

        // 1. Remove expiry, update status
        tenantService.onSubscriptionActive(tenantId, request);


        return ResponseEntity.ok().build();
    }
    @PostMapping("/{id}/wallet-funded")
    public void walletFunded(@PathVariable UUID id) {
        tenantService.onWalletFunded(id);
    }

    @PostMapping("/{id}/billing-state")
    public void billingState(
            @PathVariable UUID id,
            @RequestParam String state) {
        tenantService.onBillingStateChanged(id, state);
    }

    @GetMapping("/{id}/config")
    public TenantProvisioningConfigResponse config(@PathVariable UUID id) {
        return tenantService.getProvisioningConfig(id);
    }

    @PostMapping("/agents/provision")
    public ResponseEntity<Map<String, Object>> provisionAgent(
            @RequestBody Map<String, Object> request) {
        log.info("Agent provision request: tenant={} username={} role={}",
                request.get("tenant_id"), request.get("username"), request.get("role"));
        Map<String, Object> result = agentProvisionService.provisionAgent(request);
        boolean approved = Boolean.TRUE.equals(result.get("approved"));
        return approved ? ResponseEntity.ok(result) : ResponseEntity.status(422).body(result);
    }
}
