package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.dto.response.ProvisioningStatusResponse;
import com.dalai.llama.tenant.dto.response.ReadinessCheckResponse;
import com.dalai.llama.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/{tenantId}")
@Tag(name = "Provisioning", description = "Tenant provisioning orchestration")
public class ProvisioningController {

    private final TenantService tenantService;

    @PostMapping("/provision")
    @Operation(summary = "Trigger provisioning")
    public void provision(@PathVariable UUID tenantId) {
        tenantService.triggerProvisioning(tenantId);
    }

    @PostMapping("/retry-provisioning")
    @Operation(summary = "Retry failed provisioning")
    public void retry(@PathVariable UUID tenantId) {
        tenantService.retryProvisioning(tenantId);
    }

    @GetMapping("/provisioning-status")
    @Operation(summary = "Get provisioning status")
    public ProvisioningStatusResponse status(@PathVariable UUID tenantId) {
        return tenantService.getProvisioningStatus(tenantId);
    }

    @GetMapping("/readiness")
    @Operation(summary = "Check readiness for provisioning")
    public ReadinessCheckResponse readiness(@PathVariable UUID tenantId) {
        return tenantService.checkReadiness(tenantId);
    }
}
