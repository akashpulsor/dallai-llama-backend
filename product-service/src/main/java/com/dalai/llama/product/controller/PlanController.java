package com.dalai.llama.product.controller;

import com.dalai.llama.product.dto.mapper.ProductMapper;
import com.dalai.llama.product.dto.response.PlanDetailResponse;
import com.dalai.llama.product.dto.response.PlanResponse;
import com.dalai.llama.product.repository.PlanEntitlementRepository;
import com.dalai.llama.product.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Tag(name = "Plans", description = "Plan and pricing management")
public class PlanController {

    private final PlanService planService;
    private final PlanEntitlementRepository entitlementRepository;
    private final ProductMapper mapper;

    @GetMapping
    @Operation(
            summary = "List all plans",
            description = "Returns all available plans across all products"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of plans",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = PlanResponse.class)))
            )
    })
    public List<PlanResponse> getAllPlans() {
        return planService.getAllPlans()
                .stream()
                .map(mapper::toPlanResponse)
                .toList();
    }

    @GetMapping("/{code}")
    @Operation(
            summary = "Get plan with entitlements",
            description = "Returns plan details including all entitlements (limits and features)"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Plan with entitlements",
                    content = @Content(schema = @Schema(implementation = PlanDetailResponse.class))
            ),
            @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    public PlanDetailResponse getPlan(
            @Parameter(description = "Plan code (e.g., AI_CC_STARTER, AI_CC_PROFESSIONAL)")
            @PathVariable String code
    ) {
        var plan = planService.getPlanByCode(code);
        var entitlement = entitlementRepository
                .findByPlan_Id(plan.getId())
                .orElseThrow();

        return mapper.toPlanDetailResponse(plan, entitlement);
    }

    @GetMapping("/product/{productCode}")
    @Operation(
            summary = "List plans by product",
            description = "Returns all plans available for a specific product"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of plans for the product",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = PlanResponse.class)))
            )
    })
    public List<PlanResponse> getPlansByProduct(
            @Parameter(description = "Product code (e.g., AI_CC, CONV_IVR)")
            @PathVariable String productCode
    ) {
        return planService.getPlansByProductCode(productCode)
                .stream()
                .map(mapper::toPlanResponse)
                .toList();
    }
}