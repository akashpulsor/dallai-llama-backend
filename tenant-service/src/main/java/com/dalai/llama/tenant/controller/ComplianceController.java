package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.dto.request.UpdateCompliancePolicyRequest;
import com.dalai.llama.tenant.dto.request.UpdateRecordingPolicyRequest;
import com.dalai.llama.tenant.dto.response.CompliancePolicyResponse;
import com.dalai.llama.tenant.dto.response.RecordingPolicyResponse;
import com.dalai.llama.tenant.service.ComplianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/{tenantId}/compliance")
@Tag(name = "Compliance", description = "Compliance & recording policies")
public class ComplianceController {

    private final ComplianceService complianceService;

    @GetMapping
    @Operation(summary = "Get compliance policy")
    public CompliancePolicyResponse getCompliance(@PathVariable UUID tenantId) {
        return complianceService.getCompliancePolicy(tenantId);
    }

    @PutMapping
    @Operation(summary = "Update compliance policy")
    public CompliancePolicyResponse updateCompliance(
            @PathVariable UUID tenantId,
            @Valid @RequestBody UpdateCompliancePolicyRequest request) {
        return complianceService.updateCompliancePolicy(tenantId, request);
    }

    @GetMapping("/recording")
    @Operation(summary = "Get recording policy")
    public RecordingPolicyResponse getRecording(@PathVariable UUID tenantId) {
        return complianceService.getRecordingPolicy(tenantId);
    }

    @PutMapping("/recording")
    @Operation(summary = "Update recording policy")
    public RecordingPolicyResponse updateRecording(
            @PathVariable UUID tenantId,
            @Valid @RequestBody UpdateRecordingPolicyRequest request) {
        return complianceService.updateRecordingPolicy(tenantId, request);
    }
}
