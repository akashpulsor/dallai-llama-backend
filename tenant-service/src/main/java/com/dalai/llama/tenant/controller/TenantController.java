package com.dalai.llama.tenant.controller;


import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.TenantDetailResponse;
import com.dalai.llama.tenant.dto.response.TenantResponse;
import com.dalai.llama.tenant.service.TenantService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Tenants", description = "Tenant lifecycle management")
public class TenantController {

    private final TenantService tenantService;
    private final MeterRegistry meterRegistry;

    @PostMapping
    @Operation(summary = "Create tenant")
    public TenantResponse create(@Valid @RequestBody CreateTenantRequest request, @AuthenticationPrincipal Jwt jwt) {

        meterRegistry.counter("tenant.create").increment();
        return tenantService.createTenant(request,jwt);
    }

    @GetMapping
    @Operation(summary = "List tenants")
    public List<TenantResponse> list() {
        return tenantService.listTenants();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get tenant")
    public TenantResponse get(@PathVariable UUID id) {
        return tenantService.getTenant(id);
    }

    @GetMapping("/{id}/details")
    @Operation(summary = "Get tenant with full details")
    public TenantDetailResponse getDetails(@PathVariable UUID id) {
        return tenantService.getTenantDetails(id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update tenant")
    public TenantResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request) {
        return tenantService.updateTenant(id, request);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate tenant")
    public void activate(@PathVariable UUID id) {
        meterRegistry.counter("tenant.activate").increment();
        tenantService.activateTenant(id);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend tenant")
    public void suspend(
            @PathVariable UUID id,
            @RequestParam String reason) {
        meterRegistry.counter("tenant.suspend").increment();
        tenantService.suspendTenant(id, reason);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete tenant")
    public void delete(@PathVariable UUID id) {
        meterRegistry.counter("tenant.delete").increment();
        tenantService.deleteTenant(id," Tenant deletion requested");
    }
}
