package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.RateCard;
import com.dalai.llama.billing.domain.entity.RatePlan;
import com.dalai.llama.billing.domain.entity.enums.BillingUnit;
import com.dalai.llama.billing.domain.entity.enums.DestinationType;
import com.dalai.llama.billing.domain.exception.RatePlanNotFoundException;
import com.dalai.llama.billing.dto.request.CreateRatePlanRequest;
import com.dalai.llama.billing.dto.response.RateCardResponse;
import com.dalai.llama.billing.dto.response.RatePlanResponse;
import com.dalai.llama.billing.repository.RateCardRepository;
import com.dalai.llama.billing.repository.RatePlanRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rate-plans")
@RequiredArgsConstructor
@Tag(name = "Rate Plans", description = "Rate plan and rate card management APIs (Platform Admin)")
public class RatePlanController {

    private final RatePlanRepository ratePlanRepository;
    private final RateCardRepository rateCardRepository;

    @GetMapping
    @Operation(summary = "List rate plans", description = "Get all available rate plans")
    public ResponseEntity<List<RatePlanResponse>> listRatePlans() {
        List<RatePlan> plans = ratePlanRepository.findAll();

        List<RatePlanResponse> responses = plans.stream()
                .map(this::toRatePlanResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Get rate plan", description = "Get rate plan details with all rate cards")
    public ResponseEntity<RatePlanDetailResponse> getRatePlan(@PathVariable String code) {
        RatePlan plan = ratePlanRepository.findByCode(code)
                .orElseThrow(() -> new RatePlanNotFoundException("Rate plan not found: " + code));

        List<RateCard> cards = rateCardRepository.findAll().stream()
                .filter(c -> c.getRatePlanId().equals(plan.getId()))
                .toList();

        List<RateCardResponse> cardResponses = cards.stream()
                .map(this::toRateCardResponse)
                .toList();

        return ResponseEntity.ok(RatePlanDetailResponse.builder()
                .id(plan.getId())
                .code(plan.getCode())
                .name(plan.getName())
                .description(plan.getDescription())
                .isDefault(plan.isDefault())
                .active(plan.isActive())
                .rateCards(cardResponses)
                .build());
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Create rate plan", description = "Create a new rate plan")
    public ResponseEntity<RatePlanResponse> createRatePlan(@Valid @RequestBody CreateRatePlanRequest request) {
        Instant now = Instant.now();

        RatePlan plan = RatePlan.builder()
                .id(UUID.randomUUID())
                .code(request.getCode())
                .name(request.getName())
                .description(request.getDescription())
                .isDefault(false)
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        ratePlanRepository.save(plan);

        return ResponseEntity.ok(toRatePlanResponse(plan));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Update rate plan", description = "Update an existing rate plan")
    public ResponseEntity<RatePlanResponse> updateRatePlan(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRatePlanRequest request
    ) {
        RatePlan plan = ratePlanRepository.findById(id)
                .orElseThrow(() -> new RatePlanNotFoundException("Rate plan not found"));

        if (request.getName() != null) {
            plan.setName(request.getName());
        }
        if (request.getDescription() != null) {
            plan.setDescription(request.getDescription());
        }
        if (request.getActive() != null) {
            plan.setActive(request.getActive());
        }
        plan.setUpdatedAt(Instant.now());

        ratePlanRepository.save(plan);

        return ResponseEntity.ok(toRatePlanResponse(plan));
    }

    @PostMapping("/{id}/rate-cards")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Add rate card", description = "Add a new rate card to a rate plan")
    public ResponseEntity<RateCardResponse> addRateCard(
            @PathVariable UUID id,
            @Valid @RequestBody CreateRateCardRequest request
    ) {
        RatePlan plan = ratePlanRepository.findById(id)
                .orElseThrow(() -> new RatePlanNotFoundException("Rate plan not found"));

        Instant now = Instant.now();

        RateCard card = RateCard.builder()
                .id(UUID.randomUUID())
                .ratePlanId(plan.getId())
                .metric(request.getMetric())
                .destinationPrefix(request.getDestinationPrefix())
                .destinationType(request.getDestinationType())
                .ratePerUnit(request.getRatePerUnit())
                .unit(request.getUnit())
                .billingIncrement(request.getBillingIncrement() != null ? request.getBillingIncrement() : 60)
                .minimumCharge(request.getMinimumCharge() != null ? request.getMinimumCharge() : 0)
                .effectiveFrom(request.getEffectiveFrom() != null ? request.getEffectiveFrom() : now)
                .effectiveTo(request.getEffectiveTo())
                .priority(request.getPriority() != null ? request.getPriority() : 0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        rateCardRepository.save(card);

        return ResponseEntity.ok(toRateCardResponse(card));
    }

    @DeleteMapping("/{planId}/rate-cards/{cardId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Delete rate card", description = "Remove a rate card from a rate plan")
    public ResponseEntity<Void> deleteRateCard(
            @PathVariable UUID planId,
            @PathVariable UUID cardId
    ) {
        RateCard card = rateCardRepository.findById(cardId)
                .filter(c -> c.getRatePlanId().equals(planId))
                .orElseThrow(() -> new IllegalArgumentException("Rate card not found"));

        rateCardRepository.delete(card);

        return ResponseEntity.noContent().build();
    }

    private RatePlanResponse toRatePlanResponse(RatePlan plan) {
        return RatePlanResponse.builder()
                .id(plan.getId())
                .code(plan.getCode())
                .name(plan.getName())
                .description(plan.getDescription())
                .isDefault(plan.isDefault())
                .active(plan.isActive())
                .build();
    }

    private RateCardResponse toRateCardResponse(RateCard card) {
        return RateCardResponse.builder()
                .id(card.getId())
                .metric(card.getMetric())
                .destinationPrefix(card.getDestinationPrefix())
                .destinationType(card.getDestinationType() != null ? card.getDestinationType().name() : null)
                .ratePerUnit(card.getRatePerUnit())
                .unit(card.getUnit().name())
                .billingIncrement(card.getBillingIncrement())
                .minimumCharge(card.getMinimumCharge())
                .effectiveFrom(card.getEffectiveFrom())
                .effectiveTo(card.getEffectiveTo())
                .priority(card.getPriority())
                .build();
    }

    // ==================== REQUEST/RESPONSE CLASSES ====================

    @lombok.Getter
    public static class UpdateRatePlanRequest {
        private String name;
        private String description;
        private Boolean active;
    }

    @lombok.Getter
    public static class CreateRateCardRequest {
        @jakarta.validation.constraints.NotBlank
        private String metric;
        private String destinationPrefix;
        private DestinationType destinationType;
        @jakarta.validation.constraints.NotNull
        private BigDecimal ratePerUnit;
        @jakarta.validation.constraints.NotNull
        private BillingUnit unit;
        private Integer billingIncrement;
        private Integer minimumCharge;
        private Instant effectiveFrom;
        private Instant effectiveTo;
        private Integer priority;
    }

    @lombok.Builder
    @lombok.Getter
    public static class RatePlanDetailResponse {
        private UUID id;
        private String code;
        private String name;
        private String description;
        private boolean isDefault;
        private boolean active;
        private List<RateCardResponse> rateCards;
    }
}
