package com.dalai.llama.product.controller;

import com.dalai.llama.product.dto.mapper.ProductMapper;
import com.dalai.llama.product.dto.request.AssignPlanRequest;
import com.dalai.llama.product.dto.response.EntitlementResponse;
import com.dalai.llama.product.dto.response.PlanAssignmentResponse;
import com.dalai.llama.product.service.EntitlementService;
import com.dalai.llama.product.service.PlanAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
@RequiredArgsConstructor
@Tag(name = "Tenant Plans", description = "Tenant plan assignment and entitlements")
public class TenantPlanController {

    private final PlanAssignmentService planAssignmentService;
    private final EntitlementService entitlementService;
    private final ProductMapper mapper;

    @GetMapping("/plan")
    @Operation(
            summary = "Get tenant's current plan",
            description = "Returns the currently active plan assigned to the tenant"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Current plan assignment",
                    content = @Content(schema = @Schema(implementation = PlanAssignmentResponse.class))
            ),
            @ApiResponse(responseCode = "404", description = "No active plan found")
    })
    public PlanAssignmentResponse getCurrentPlan(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId
    ) {
        return mapper.toPlanAssignmentResponse(
                planAssignmentService.getActivePlan(tenantId)
        );
    }

    @PostMapping("/plan")
    @Operation(
            summary = "Assign plan to tenant",
            description = "Assign a new plan to the tenant. If a plan is already assigned, it will be replaced."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Plan assigned successfully",
                    content = @Content(schema = @Schema(implementation = PlanAssignmentResponse.class))
            ),
            @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    public PlanAssignmentResponse assignPlan(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId,
            @RequestBody @Valid AssignPlanRequest request
    ) {
        return mapper.toPlanAssignmentResponse(
                planAssignmentService.assignPlan(tenantId, request.getPlanId())
        );
    }

    @GetMapping("/entitlements")
    @Operation(
            summary = "Get tenant entitlements",
            description = "Returns the effective entitlements (limits and features) for the tenant based on their plan"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Tenant entitlements",
                    content = @Content(schema = @Schema(implementation = EntitlementResponse.class))
            ),
            @ApiResponse(responseCode = "404", description = "No active plan found")
    })
    public EntitlementResponse getEntitlements(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId
    ) {
        return mapper.toEntitlementResponse(
                entitlementService.getEffectiveEntitlements(tenantId)
        );
    }
}