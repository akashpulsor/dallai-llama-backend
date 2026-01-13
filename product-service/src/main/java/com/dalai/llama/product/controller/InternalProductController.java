package com.dalai.llama.product.controller;

import com.dalai.llama.product.domain.entity.Plan;
import com.dalai.llama.product.dto.mapper.ProductMapper;
import com.dalai.llama.product.dto.response.DidResponse;
import com.dalai.llama.product.dto.response.EntitlementResponse;
import com.dalai.llama.product.dto.response.PlanAssignmentResponse;
import com.dalai.llama.product.repository.DidRepository;
import com.dalai.llama.product.repository.PlanRepository;
import com.dalai.llama.product.repository.PstnChannelBundleRepository;
import com.dalai.llama.product.service.EntitlementService;
import com.dalai.llama.product.service.PlanAssignmentService;
import com.dalai.llama.product.service.didww.DidwwApiService;
import com.dalai.llama.product.service.didww.DidwwProvisioningService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
@Tag(name = "Internal APIs", description = "Service-to-service APIs")
@Hidden
public class InternalProductController {

    private final PlanAssignmentService planAssignmentService;
    private final EntitlementService entitlementService;
    private final PlanRepository planRepository;
    private final DidRepository didRepository;
    private final PstnChannelBundleRepository channelBundleRepository;
    private final ProductMapper mapper;
    private final DidwwProvisioningService didwwProvisioningService;
    /**
     * Called by Tenant Service during provisioning to assign default plan
     */
    @PostMapping("/tenants/{tenantId}/default-plan")
    @Operation(summary = "Assign default plan to tenant")
    public ResponseEntity<PlanAssignmentResponse> assignDefaultPlan(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "AI_CC") String productCode
    ) {
        log.info("Assigning default plan for tenant {} with product {}", tenantId, productCode);

        // Find default plan for the product
        Plan defaultPlan = planRepository.findByProduct_CodeAndIsDefaultTrue(productCode)
                .orElse(null);

        if (defaultPlan == null) {
            log.warn("No default plan found for product {}", productCode);
            return ResponseEntity.notFound().build();
        }

        var assignment = planAssignmentService.assignPlan(tenantId, defaultPlan.getId());
        log.info("Assigned default plan {} to tenant {}", defaultPlan.getCode(), tenantId);

        return ResponseEntity.ok(mapper.toPlanAssignmentResponse(assignment));
    }

    /**
     * Called by Tenant Service during deprovisioning (compensation)
     */
    @DeleteMapping("/tenants/{tenantId}/plan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove plan assignment (compensation)")
    public void removePlan(@PathVariable UUID tenantId) {
        log.info("Removing plan for tenant {}", tenantId);
        planAssignmentService.removePlan(tenantId);
        entitlementService.invalidateCache(tenantId);
    }

    /**
     * Called by PBX Core / Billing for authorization checks
     */
    @GetMapping("/tenants/{tenantId}/entitlements")
    @Operation(summary = "Get tenant entitlements for authorization")
    public EntitlementResponse getEntitlements(@PathVariable UUID tenantId) {
        return mapper.toEntitlementResponse(
                entitlementService.getEffectiveEntitlements(tenantId)
        );
    }

    /**
     * Called by PBX Core to get DID info by number
     */
    @GetMapping("/tenants/{tenantId}/dids/{number}")
    @Operation(summary = "Get DID by number")
    public ResponseEntity<DidResponse> getDidByNumber(
            @PathVariable UUID tenantId,
            @PathVariable String number
    ) {
        return didRepository.findByTenantIdAndNumber(tenantId, number)
                .map(mapper::toDidResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Called by Tenant Service to check purchased DIDs
     */
    @GetMapping("/tenants/{tenantId}/dids/purchased")
    @Operation(summary = "Get purchased DIDs for tenant")
    public List<DidResponse> getPurchasedDids(@PathVariable UUID tenantId) {
        return didRepository.findByTenantId(tenantId).stream()
                .map(mapper::toDidResponse)
                .toList();
    }

    /**
     * Called by PBX Core to get available PSTN channels
     */
    @GetMapping("/tenants/{tenantId}/channels")
    @Operation(summary = "Get available PSTN channels")
    public ResponseEntity<Map<String, Object>> getAvailableChannels(@PathVariable UUID tenantId) {
        var bundles = channelBundleRepository.findByTenantId(tenantId);

        int totalChannels = bundles.stream().mapToInt(b -> b.getTotalChannels()).sum();
        int activeChannels = bundles.stream().mapToInt(b -> b.getActiveChannels()).sum();
        int inboundChannels = bundles.stream().mapToInt(b -> b.getInboundChannels()).sum();
        int outboundChannels = bundles.stream().mapToInt(b -> b.getOutboundChannels()).sum();

        return ResponseEntity.ok(Map.of(
                "totalChannels", totalChannels,
                "activeChannels", activeChannels,
                "availableChannels", totalChannels - activeChannels,
                "inboundChannels", inboundChannels,
                "outboundChannels", outboundChannels
        ));
    }

    /**
     * Called by Tenant Service to configure DIDWW trunk with Kamailio IP
     */
    @PostMapping("/tenants/{tenantId}/didww/configure")
    @Operation(summary = "Configure DIDWW trunk with Kamailio IP")
    public ResponseEntity<Map<String, String>> configureDidwwTrunk(
            @PathVariable UUID tenantId,
            @RequestBody Map<String, String> request
    ) {
        String kamailioIp = request.get("kamailioIp");
        int kamailioPort = Integer.parseInt(request.getOrDefault("kamailioPort", "5060"));

        log.info("Configuring DIDWW trunk for tenant {} with IP {}:{}", tenantId, kamailioIp, kamailioPort);


        didwwProvisioningService.configureSip(tenantId.toString(), kamailioIp, kamailioPort);
        return ResponseEntity.ok(Map.of(
                "status", "configured",
                "kamailioIp", kamailioIp,
                "kamailioPort", String.valueOf(kamailioPort)
        ));
    }
}