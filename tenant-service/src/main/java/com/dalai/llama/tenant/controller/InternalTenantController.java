package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.request.ProvisionTenantUserRequest;
import com.dalai.llama.tenant.dto.request.SubscriptionActiveRequest;
import com.dalai.llama.tenant.dto.response.ProvisionedUserResult;
import com.dalai.llama.tenant.dto.response.SubscriptionActiveResponse;
import com.dalai.llama.tenant.dto.response.TenantResponse;
import com.dalai.llama.tenant.service.CredentialDeliveryService;
import com.dalai.llama.tenant.service.TenantAppService;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.service.impl.AgentProvisionService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Slf4j
@Hidden
public class InternalTenantController {

    private final TenantService tenantService;
    private final AgentProvisionService agentProvisionService;
    private final TenantAppService tenantAppService;
    private final CredentialDeliveryService credentialDeliveryService;

    @GetMapping("/api/v1/internal/tenants/{id}")
    public TenantResponse get(@PathVariable UUID id) {
        return tenantService.getTenant(id);
    }

    @GetMapping("/api/v1/internal/tenants/apps/did/{did}")
    public ResponseEntity<Map<String, Object>> getTenantByDid(
            @PathVariable String did) {

        return tenantAppService.getByDid(did)
                .map(this::toResponseMap)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/v1/internal/tenants/apps/tenant/{tenantId}")
    public ResponseEntity<Map<String, Object>> getTenantByTenantId(
            @PathVariable UUID tenantId) {

        return tenantAppService.getByTenantId(tenantId)
                .map(this::toResponseMap)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/v1/internal/tenants/{tenantId}/activate
     * Called by product-service after payment succeeds to create TenantApp and trigger provisioning.
     */
    @PostMapping("/api/v1/internal/tenants/{tenantId}/activate")
    public ResponseEntity<SubscriptionActiveResponse> activateSubscription(
            @PathVariable UUID tenantId,
            @RequestBody SubscriptionActiveRequest request) {

        log.info("Subscription activation request: tenant={} subscription={} product={}",
                tenantId, request.subscriptionId(), request.productCode());

        SubscriptionActiveResponse response = tenantService.activateSubscription(request);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toResponseMap(TenantApp app) {
        Map<String, Object> response = new HashMap<>();

        response.put("id", app.getId());
        response.put("tenant_id", app.getTenant().getId());
        response.put("subscription_id", app.getSubscriptionId());
        response.put("did_number", app.getDidNumber());
        response.put("subdomain", app.getSubdomain());
        response.put("namespace", app.getNamespace());
        response.put("app_type", app.getAppType());
        response.put("enabled", app.getEnabled());
        response.put("product_code", app.getProductCode());
        response.put("plan_tier", app.getPlanTier());
        response.put("sip_endpoint_username", app.getSipEndpointUsername());
        response.put("tenant_trunk_username", app.getTenantTrunkUsername());
        response.put("deployment_status", app.getDeploymentStatus());
        response.put("kamailio_synced", app.getKamailioSynced());
        response.put("freepbx_synced", app.getFreepbxSynced());

        return response;
    }
    /**
     * POST /api/v1/internal/tenants/agents/provision
     */
    @PostMapping("/api/v1/internal/tenants/agents/provision")
    public ResponseEntity<Map<String, Object>> provisionAgent(
            @RequestBody Map<String, Object> request) {

        log.info("Agent provision request: tenant={} username={} role={}",
                request.get("tenant_id"),
                request.get("username"),
                request.get("role"));

        Map<String, Object> result =
                agentProvisionService.provisionAgent(request);

        boolean approved = Boolean.TRUE.equals(result.get("approved"));

        return approved
                ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }

    @PostMapping("/api/v1/internal/tenant/users")
    public ResponseEntity<ProvisionedUserResult> provisionTenantUser(
            @RequestBody ProvisionTenantUserRequest request) {
        return ResponseEntity.ok(credentialDeliveryService.provisionTenantUser(request));
    }

    @PatchMapping("/api/v1/internal/tenant/users/{id}/password")
    public ResponseEntity<Void> changePassword(
            @PathVariable UUID id,
            @RequestBody ChangePasswordRequest request) {
        credentialDeliveryService.changePassword(id, request.newPassword(), request.requesterSubject());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/internal/tenant/users/{id}/deprovision")
    public ResponseEntity<Void> deprovisionTenantUser(
            @PathVariable UUID id,
            @RequestBody DeprovisionRequest request) {
        credentialDeliveryService.deprovisionTenantUser(id, request.reason(), request.requesterSubject());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/internal/tenant/users/{id}/disable")
    public ResponseEntity<Void> disableTenantUser(@PathVariable UUID id) {
        credentialDeliveryService.deprovisionTenantUser(id, "Disabled via internal API", "SYSTEM");
        return ResponseEntity.noContent().build();
    }

    public record ChangePasswordRequest(String newPassword, String requesterSubject) {}

    public record DeprovisionRequest(String reason, String requesterSubject) {}
}
