package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.MyTenantResponse;
import com.dalai.llama.tenant.dto.response.TenantDetailResponse;
import com.dalai.llama.tenant.dto.response.TenantAppSummary;
import com.dalai.llama.tenant.dto.response.TenantResponse;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final TenantAppRepository tenantAppRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(
            @Valid @RequestBody CreateTenantRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tenantService.createTenant(request, jwt);
    }

    @GetMapping("/me")
    public ResponseEntity<MyTenantResponse> getMyTenant(@AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();

        return tenantService.findByAdminUserId(keycloakUserId)
                .map(t -> ResponseEntity.ok(MyTenantResponse.fromTenant(t)))
                .orElseGet(() -> ResponseEntity.ok(MyTenantResponse.empty()));
    }

        @GetMapping("/me/apps")
    public ResponseEntity<List<TenantAppSummary>> getMyApps(@AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return tenantService.findByAdminUserId(keycloakUserId)
                .map(tenant -> {
                    List<TenantAppSummary> apps = tenantAppRepository.findAllByTenantId(tenant.getId())
                            .stream().map(TenantAppSummary::from).toList();
                    return ResponseEntity.ok(apps);
                })
                .orElseGet(() -> ResponseEntity.ok(List.of()));
    }

    @GetMapping
    public List<TenantResponse> list() {
        return tenantService.listTenants();
    }

    @GetMapping("/{id}")
    public TenantResponse get(@PathVariable UUID id) {
        return tenantService.getTenant(id);
    }

    @GetMapping("/{id}/details")
    public TenantDetailResponse getDetails(@PathVariable UUID id) {
        return tenantService.getTenantDetails(id);
    }

    @PutMapping("/{id}")
    public TenantResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request) {
        return tenantService.updateTenant(id, request);
    }

    @PostMapping("/{id}/activate")
    public void activate(@PathVariable UUID id) {
        tenantService.activateTenant(id);
    }

    @PostMapping("/{id}/suspend")
    public void suspend(@PathVariable UUID id, @RequestParam String reason) {
        tenantService.suspendTenant(id, reason);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id,
                       @RequestParam(defaultValue = "Tenant deletion requested") String reason) {
        tenantService.deleteTenant(id, reason);
    }

}