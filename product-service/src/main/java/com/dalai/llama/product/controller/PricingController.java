package com.dalai.llama.product.controller;

import com.dalai.llama.product.dto.response.PlanPricingResponse;
import com.dalai.llama.product.service.impl.PlanPricingService;
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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Pricing", description = "Plan pricing with AI stack details")
public class PricingController {

    private final PlanPricingService pricingService;

    @GetMapping("/products/{productCode}/plans")
    @Operation(summary = "Get plans with pricing for a product")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of plans with pricing",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = PlanPricingResponse.class)))),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public List<PlanPricingResponse> getProductPlans(
            @Parameter(description = "Product code (e.g., AI_CC, CONV_IVR)")
            @PathVariable String productCode) {
        return pricingService.getPlansByProductCode(productCode);
    }

    @GetMapping("/plans/{planCode}/pricing")
    @Operation(summary = "Get pricing details for a specific plan")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Plan pricing details",
                    content = @Content(schema = @Schema(implementation = PlanPricingResponse.class))),
            @ApiResponse(responseCode = "404", description = "Plan not found")
    })
    public PlanPricingResponse getPlanPricing(
            @Parameter(description = "Plan code (e.g., AICC_STANDARD)")
            @PathVariable String planCode) {
        return pricingService.getPlanPricing(planCode);
    }
}

