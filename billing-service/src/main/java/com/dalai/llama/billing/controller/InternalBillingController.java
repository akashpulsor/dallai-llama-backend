package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.BillingState;
import com.dalai.llama.billing.domain.entity.UsageRecord;
import com.dalai.llama.billing.domain.entity.enums.BillingStateType;
import com.dalai.llama.billing.domain.entity.enums.BillingUnit;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.domain.entity.enums.UsageMetric;
import com.dalai.llama.billing.dto.response.CallAuthorizationResponse;
import com.dalai.llama.billing.repository.BillingStateRepository;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import com.dalai.llama.billing.service.BillingStateService;
import com.dalai.llama.billing.service.CallAuthorizationService;
import com.dalai.llama.billing.service.WalletService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/billing/{tenantId}")
@RequiredArgsConstructor
@Tag(name = "Internal Billing", description = "Internal APIs for service-to-service communication")
@Hidden
public class InternalBillingController {

    private final WalletService walletService;
    private final CallAuthorizationService callAuthorizationService;
    private final BillingStateRepository billingStateRepository;
    private final BillingStateService billingStateService;
    private final UsageRecordRepository usageRecordRepository;

    // ==================== WALLET MANAGEMENT ====================

    @PostMapping("/wallet")
    @Operation(summary = "Create wallet", description = "Create wallet for new tenant (called by Tenant Service)")
    public ResponseEntity<Void> createWallet(@PathVariable UUID tenantId) {
        walletService.createWallet(tenantId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/wallet")
    @Operation(summary = "Delete wallet", description = "Delete wallet for tenant (compensation)")
    public ResponseEntity<Void> deleteWallet(@PathVariable UUID tenantId) {
        walletService.deleteWallet(tenantId);
        return ResponseEntity.noContent().build();
    }

    // ==================== CALL AUTHORIZATION ====================

    @GetMapping("/authorize-call")
    @Operation(summary = "Authorize outbound call", description = "Check if tenant can make outbound calls (called by PBX Core)")
    public ResponseEntity<CallAuthorizationResponse> authorizeCall(@PathVariable UUID tenantId) {
        boolean authorized = callAuthorizationService.authorizeCall(tenantId);

        BillingState state = billingStateRepository.findByTenantId(tenantId)
                .orElseGet(() -> BillingState.createDefault(tenantId));

        String reason = null;
        if (!authorized) {
            reason = switch (state.getState()) {
                case BLOCKED -> "Account blocked: " + state.getBlockReason();
                case SUSPENDED -> "Account suspended";
                default -> "Billing state does not permit calls";
            };
        }

        return ResponseEntity.ok(CallAuthorizationResponse.builder()
                .authorized(authorized)
                .state(state.getState().name())
                .reason(reason)
                .remainingBalance(walletService.getBalance(tenantId))
                .build());
    }

    // ==================== BILLING STATE ====================

    @GetMapping("/billing-state")
    @Operation(summary = "Get billing state", description = "Get current billing state (called by PBX Core)")
    public ResponseEntity<InternalBillingStateResponse> getBillingState(@PathVariable UUID tenantId) {
        BillingState state = billingStateRepository.findByTenantId(tenantId)
                .orElseGet(() -> BillingState.createDefault(tenantId));

        boolean canMakeCalls = state.getState() == BillingStateType.ACTIVE ||
                state.getState() == BillingStateType.GRACE;

        return ResponseEntity.ok(InternalBillingStateResponse.builder()
                .tenantId(tenantId)
                .state(state.getState().name())
                .canMakeCalls(canMakeCalls)
                .graceExpiresAt(state.getGraceExpiresAt())
                .blockReason(state.getBlockReason())
                .build());
    }

    // ==================== DID RENTAL ====================

    @PostMapping("/did-rental")
    @Operation(summary = "Record DID rental", description = "Record DID rental charge (called by Product Service)")
    public ResponseEntity<Void> recordDidRental(
            @PathVariable UUID tenantId,
            @Valid @RequestBody DidRentalRequest request
    ) {
        // Create usage record
        UsageRecord record = UsageRecord.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .metric(UsageMetric.DID_RENTAL)
                .quantity(BigDecimal.ONE)
                .unit(BillingUnit.MONTH)
                .unitCost(request.getAmount())
                .totalCost(request.getAmount())
                .sourceType("DID")
                .sourceId(request.getDidId())
                .description("DID rental: " + request.getDidNumber())
                .recordedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        usageRecordRepository.save(record);

        // Debit wallet
        walletService.debit(
                tenantId,
                request.getAmount(),
                "DID_RENTAL:" + request.getDidNumber()
        );

        // Evaluate billing state
        billingStateService.evaluateState(tenantId);

        return ResponseEntity.ok().build();
    }

    // ==================== USAGE RECORDING ====================

    @PostMapping("/usage")
    @Operation(summary = "Record usage", description = "Record AI/storage usage (called by PBX Core)")
    public ResponseEntity<Void> recordUsage(
            @PathVariable UUID tenantId,
            @Valid @RequestBody RecordUsageRequest request
    ) {
        UsageRecord record = UsageRecord.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .metric(request.getMetric())
                .quantity(request.getQuantity())
                .unit(request.getUnit())
                .unitCost(request.getUnitCost())
                .totalCost(request.getTotalCost())
                .sourceType(request.getSourceType())
                .sourceId(request.getSourceId())
                .description(request.getDescription())
                .recordedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        usageRecordRepository.save(record);

        // Debit wallet if there's a cost
        if (request.getTotalCost() != null && request.getTotalCost().signum() > 0) {
            walletService.debit(
                    tenantId,
                    request.getTotalCost(),
                    "USAGE:" + request.getMetric().name()
            );

            billingStateService.evaluateState(tenantId);
        }

        return ResponseEntity.ok().build();
    }

    // ==================== MANUAL ADJUSTMENTS ====================

    @PostMapping("/adjust/credit")
    @Operation(summary = "Manual credit", description = "Apply manual credit adjustment (admin only)")
    public ResponseEntity<Void> manualCredit(
            @PathVariable UUID tenantId,
            @Valid @RequestBody AdjustmentRequest request
    ) {
        walletService.credit(tenantId, request.getAmount(), "ADJUSTMENT:" + request.getReason());
        billingStateService.evaluateState(tenantId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/adjust/debit")
    @Operation(summary = "Manual debit", description = "Apply manual debit adjustment (admin only)")
    public ResponseEntity<Void> manualDebit(
            @PathVariable UUID tenantId,
            @Valid @RequestBody AdjustmentRequest request
    ) {
        walletService.debit(tenantId, request.getAmount(), "ADJUSTMENT:" + request.getReason());
        billingStateService.evaluateState(tenantId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/wallet/balance")
    @Operation(summary = "Get balance", description = "Get wallet balance (called by Product Service)")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(walletService.getBalance(tenantId));
    }
    // ==================== REQUEST/RESPONSE CLASSES ====================

    @lombok.Getter
    public static class DidRentalRequest {
        @jakarta.validation.constraints.NotNull
        private UUID didId;
        @jakarta.validation.constraints.NotBlank
        private String didNumber;
        @jakarta.validation.constraints.NotNull
        private BigDecimal amount;
    }

    @lombok.Getter
    public static class RecordUsageRequest {
        @jakarta.validation.constraints.NotNull
        private UsageMetric metric;
        @jakarta.validation.constraints.NotNull
        private BigDecimal quantity;
        @jakarta.validation.constraints.NotNull
        private BillingUnit unit;
        private BigDecimal unitCost;
        private BigDecimal totalCost;
        private String sourceType;
        private UUID sourceId;
        private String description;
    }

    @lombok.Getter
    public static class AdjustmentRequest {
        @jakarta.validation.constraints.NotNull
        @jakarta.validation.constraints.DecimalMin("0.01")
        private BigDecimal amount;
        @jakarta.validation.constraints.NotBlank
        private String reason;
    }

    @lombok.Builder
    @lombok.Getter
    public static class InternalBillingStateResponse {
        private UUID tenantId;
        private String state;
        private boolean canMakeCalls;
        private Instant graceExpiresAt;
        private String blockReason;
    }
}
