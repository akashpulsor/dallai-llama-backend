package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.MyTenantResponse;
import com.dalai.llama.tenant.dto.response.TenantDetailResponse;
import com.dalai.llama.tenant.dto.response.TenantAppSummary;
import com.dalai.llama.tenant.dto.response.TenantResponse;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.service.client.ProductServiceClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final TenantAppRepository tenantAppRepository;
    private final ProductServiceClient productServiceClient;

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

    // ═══════════════════════════════════════════════════════════
    // DID MANAGEMENT — list + release DIDs from dashboard UI
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/me/dids")
    public ResponseEntity<List<Map<String, Object>>> getMyDids(@AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return tenantService.findByAdminUserId(keycloakUserId)
                .map(tenant -> ResponseEntity.ok(productServiceClient.listDids(tenant.getId())))
                .orElseGet(() -> ResponseEntity.ok(List.of()));
    }

    @DeleteMapping("/me/dids/{didId}")
    public ResponseEntity<Void> releaseDid(
            @PathVariable UUID didId,
            @AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return tenantService.findByAdminUserId(keycloakUserId)
                .map(tenant -> {
                    productServiceClient.releaseDid(tenant.getId(), didId);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════
    // SUBSCRIPTION MANAGEMENT — cancel subscription from dashboard UI
    // ═══════════════════════════════════════════════════════════

    @DeleteMapping("/me/subscriptions/{subscriptionId}")
    public ResponseEntity<Void> cancelSubscription(
            @PathVariable UUID subscriptionId,
            @AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return tenantService.findByAdminUserId(keycloakUserId)
                .map(tenant -> {
                    // Verify the subscription belongs to this tenant via app
                    boolean owns = tenantAppRepository.existsByTenantIdAndSubscriptionId(
                            tenant.getId(), subscriptionId);
                    if (!owns) {
                        return ResponseEntity.status(403).<Void>build();
                    }
                    productServiceClient.cancelSubscription(subscriptionId);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}