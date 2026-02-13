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
@Tag(name = "Provisioning", description = "Tenant provisioning and lifecycle orchestration")
public class ProvisioningController {

    private final TenantService tenantService;

    @PostMapping("/provision")
    @Operation(summary = "Trigger initial technical provisioning")
    public void provision(@PathVariable UUID tenantId) {
        tenantService.triggerProvisioning(tenantId);
    }

    @PostMapping("/retry-provisioning")
    @Operation(summary = "Retry failed provisioning from the last failed state")
    public void retry(@PathVariable UUID tenantId) {
        // PM Logic: This moves state from ERROR back to the previous failed state
        tenantService.retryProvisioning(tenantId);
    }

    @PostMapping("/restart")
    @Operation(summary = "Clean up and restart the entire provisioning stack from zero")
    public void restart(@PathVariable UUID tenantId,
                        @RequestParam(required = false) String reason) {
        // PM Logic: Moves state to PROVISIONING_RESTART to trigger a fresh build
        tenantService.restartProvisioning(tenantId, reason != null ? reason : "Manual restart triggered");
    }

    @PostMapping("/kyc/reject")
    @Operation(summary = "Reject KYC documents and stop progress")
    public void rejectKyc(@PathVariable UUID tenantId,
                          @RequestParam String reason) {
        tenantService.rejectKyc(tenantId, reason);
    }

    @PostMapping("/kyc/approve")
    @Operation(summary = "Approve KYC documents to allow billing/provisioning")
    public void approveKyc(@PathVariable UUID tenantId) {
        tenantService.approveKyc(tenantId);
    }

    @DeleteMapping
    @Operation(summary = "Delete tenant and stop any active provisioning")
    public void delete(@PathVariable UUID tenantId,
                       @RequestParam(required = false) String reason) {
        tenantService.deleteTenant(tenantId, reason != null ? reason : "Tenant deletion requested");
    }

    @GetMapping("/provisioning-status")
    @Operation(summary = "Get current state and progress")
    public ProvisioningStatusResponse status(@PathVariable UUID tenantId) {
        return tenantService.getProvisioningStatus(tenantId);
    }

    @GetMapping("/readiness")
    @Operation(summary = "Check if DID, SIP, and KYC are ready for provisioning")
    public ReadinessCheckResponse readiness(@PathVariable UUID tenantId) {
        return tenantService.checkReadiness(tenantId);
    }
}