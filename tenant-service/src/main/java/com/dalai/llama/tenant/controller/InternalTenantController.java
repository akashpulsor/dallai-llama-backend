package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.dto.response.TenantProvisioningConfigResponse;
import com.dalai.llama.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/tenants")
@Hidden
public class InternalTenantController {

    private final TenantService tenantService;

    @PostMapping("/{id}/plan-assigned")
    public void planAssigned(
            @PathVariable UUID id,
            @RequestParam UUID planId,
            @RequestParam String planCode) {
        tenantService.onPlanAssigned(id, planId, planCode);
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
}
