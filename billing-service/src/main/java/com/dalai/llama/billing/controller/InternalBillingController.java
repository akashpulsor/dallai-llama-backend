package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.BillingState;
import com.dalai.llama.billing.domain.entity.RecurringCharge;
import com.dalai.llama.billing.domain.entity.UsageRecord;
import com.dalai.llama.billing.domain.entity.enums.BillingStateType;
import com.dalai.llama.billing.domain.entity.enums.BillingUnit;
import com.dalai.llama.billing.domain.entity.enums.UsageMetric;
import com.dalai.llama.billing.dto.response.CallAuthorizationResponse;
import com.dalai.llama.billing.repository.BillingStateRepository;
import com.dalai.llama.billing.repository.RecurringChargeRepository;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import com.dalai.llama.billing.service.BillingStateService;
import com.dalai.llama.billing.service.CallAuthorizationService;
import com.dalai.llama.billing.service.WalletService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Internal APIs for service-to-service communication.
 *
 * Called by:
 * - product-service: subscription, DID provisioning
 * - tenant-service: wallet creation
 * - pbx-core: call authorization, usage recording
 *
 * Path: /api/v1/internal/tenants/{tenantId}/...
 * No JWT auth - relies on service mesh / internal network.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}")
@RequiredArgsConstructor
@Tag(name = "Internal Billing", description = "Internal APIs for service-to-service communication")
@Hidden
public class InternalBillingController {

    private final WalletService walletService;
    private final CallAuthorizationService callAuthorizationService;
    private final BillingStateRepository billingStateRepository;
    private final BillingStateService billingStateService;
    private final UsageRecordRepository usageRecordRepository;
    private final RecurringChargeRepository recurringChargeRepository;

    // ==================== WALLET MANAGEMENT ====================

    /**
     * POST /api/v1/internal/tenants/{tenantId}/wallet
     * Create wallet for tenant (called by tenant-service on org setup)
     */
    @PostMapping("/wallet")
    @Operation(summary = "Create wallet")
    public ResponseEntity<WalletResponse> createWallet(@PathVariable UUID tenantId) {
        walletService.createWallet(tenantId);
        BigDecimal balance = walletService.getBalance(tenantId);
        log.info("Created wallet for tenant {} with balance {}", tenantId, balance);
        return ResponseEntity.ok(new WalletResponse(tenantId, balance, "INR", "ACTIVE"));
    }

    /**
     * GET /api/v1/internal/tenants/{tenantId}/wallet/balance
     * Get wallet balance (called by product-service, tenant-service)
     */
    @GetMapping("/wallet/balance")
    @Operation(summary = "Get wallet balance")
    public ResponseEntity<WalletBalanceResponse> getWalletBalance(@PathVariable UUID tenantId) {
        BigDecimal balance = walletService.getBalance(tenantId);
        return ResponseEntity.ok(new WalletBalanceResponse(balance, "INR"));
    }

    /**
     * POST /api/v1/internal/tenants/{tenantId}/wallet/charge
     * Charge wallet for subscription (called by product-service on subscribe)
     *
     * Deducts: platform fee + agent fees + DID fees
     */
    @PostMapping("/wallet/charge")
    @Operation(summary = "Charge wallet for subscription")
    public ResponseEntity<ChargeResponse> chargeWallet(
            @PathVariable UUID tenantId,
            @Valid @RequestBody ChargeRequest request) {

        log.info("Charging tenant {} - amount: ₹{}, type: {}", tenantId, request.amount, request.type);

        // Validate balance
        BigDecimal currentBalance = walletService.getBalance(tenantId);
        if (currentBalance.compareTo(request.amount) < 0) {
            return ResponseEntity.badRequest()
                    .body(new ChargeResponse(false, currentBalance, "Insufficient balance"));
        }

        // Debit wallet
        String description = request.description != null ? request.description : request.type;
        walletService.debit(tenantId, request.amount, request.type + ":" + description, request.subscriptionId);

        // Re-evaluate billing state
        billingStateService.evaluateState(tenantId);

        BigDecimal newBalance = walletService.getBalance(tenantId);
        log.info("Charged tenant {} - ₹{}, new balance: ₹{}", tenantId, request.amount, newBalance);

        return ResponseEntity.ok(new ChargeResponse(true, newBalance, "Charged successfully"));
    }

    /**
     * DELETE /api/v1/internal/tenants/{tenantId}/wallet
     * Delete wallet (rollback/compensation)
     */
    @DeleteMapping("/wallet")
    @Operation(summary = "Delete wallet")
    public ResponseEntity<Void> deleteWallet(@PathVariable UUID tenantId) {
        walletService.deleteWallet(tenantId);
        log.info("Deleted wallet for tenant {}", tenantId);
        return ResponseEntity.noContent().build();
    }

    // ==================== RECURRING CHARGES ====================

    /**
     * POST /api/v1/internal/tenants/{tenantId}/recurring-charges
     * Create recurring charge for auto-billing (called by product-service on subscribe)
     *
     * Types: PLATFORM_FEE, DID_RENTAL, AGENT_FEE
     */
    @PostMapping("/recurring-charges")
    @Operation(summary = "Create recurring charge")
    public ResponseEntity<RecurringChargeResponse> createRecurringCharge(
            @PathVariable UUID tenantId,
            @Valid @RequestBody RecurringChargeRequest request) {

        log.info("Creating recurring charge for tenant {} - type: {}, amount: ₹{}",
                tenantId, request.type, request.amount);

        RecurringCharge charge = RecurringCharge.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .type(request.type)
                .amount(request.amount)
                .frequency(request.frequency != null ? request.frequency : "MONTHLY")
                .nextChargeDate(LocalDate.now().plusMonths(1))
                .status("ACTIVE")
                .sourceType(request.sourceType)
                .subscriptionId(request.subscriptionId)
                .sourceId(request.sourceId)
                .description(request.description)
                .createdAt(Instant.now())
                .build();

        recurringChargeRepository.save(charge);

        log.info("Created recurring charge {} for tenant {}", charge.getId(), tenantId);

        return ResponseEntity.ok(new RecurringChargeResponse(
                charge.getId(),
                charge.getType(),
                charge.getAmount(),
                charge.getNextChargeDate()
        ));
    }

    // ==================== CALL AUTHORIZATION ====================

    /**
     * GET /api/v1/internal/tenants/{tenantId}/authorize-call
     * Authorize call (called by pbx-core before each call)
     */
    @GetMapping("/authorize-call")
    @Operation(summary = "Authorize call")
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

    /**
     * GET /api/v1/internal/tenants/{tenantId}/billing-state
     * Get billing state (called by product-service, pbx-core)
     */
    @GetMapping("/billing-state")
    @Operation(summary = "Get billing state")
    public ResponseEntity<BillingStateResponse> getBillingState(@PathVariable UUID tenantId) {
        BillingState state = billingStateRepository.findByTenantId(tenantId)
                .orElseGet(() -> BillingState.createDefault(tenantId));

        boolean canMakeCalls = state.getState() == BillingStateType.ACTIVE ||
                state.getState() == BillingStateType.GRACE;

        return ResponseEntity.ok(BillingStateResponse.builder()
                .tenantId(tenantId)
                .state(state.getState().name())
                .canMakeCalls(canMakeCalls)
                .graceExpiresAt(state.getGraceExpiresAt())
                .blockReason(state.getBlockReason())
                .build());
    }

    // ==================== DID RENTAL ====================

    /**
     * POST /api/v1/internal/tenants/{tenantId}/did-rental
     * Record DID rental charge (called by product-service monthly billing job)
     */
    @PostMapping("/did-rental")
    @Operation(summary = "Record DID rental")
    public ResponseEntity<Void> recordDidRental(
            @PathVariable UUID tenantId,
            @Valid @RequestBody DidRentalRequest request) {

        // Create usage record
        UsageRecord record = UsageRecord.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .metric(UsageMetric.DID_RENTAL)
                .quantity(BigDecimal.ONE)
                .unit(BillingUnit.MONTH)
                .unitCost(request.amount)
                .totalCost(request.amount)
                .sourceType("DID")
                .sourceId(request.didId)
                .description("DID rental: " + request.didNumber)
                .recordedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        usageRecordRepository.save(record);

        // Debit wallet
        walletService.debit(tenantId, request.amount, "DID_RENTAL:" + request.didNumber,request.subscriptionId);

        // Evaluate billing state
        billingStateService.evaluateState(tenantId);

        log.info("Recorded DID rental for tenant {}: {} @ ₹{}", tenantId, request.didNumber, request.amount);

        return ResponseEntity.ok().build();
    }

    // ==================== USAGE RECORDING ====================

    /**
     * POST /api/v1/internal/tenants/{tenantId}/usage
     * Record usage (AI, storage, calls) - called by pbx-core, ai-service
     */
    @PostMapping("/usage")
    @Operation(summary = "Record usage")
    public ResponseEntity<Void> recordUsage(
            @PathVariable UUID tenantId,
            @Valid @RequestBody RecordUsageRequest request) {

        UsageRecord record = UsageRecord.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .metric(request.metric)
                .quantity(request.quantity)
                .unit(request.unit)
                .unitCost(request.unitCost)
                .totalCost(request.totalCost)
                .sourceType(request.sourceType)
                .sourceId(request.sourceId)
                .description(request.description)
                .recordedAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        usageRecordRepository.save(record);

        // Debit wallet if there's a cost
        if (request.totalCost != null && request.totalCost.signum() > 0) {
            walletService.debit(tenantId, request.totalCost, "USAGE:" + request.metric.name(),request.subscriptionId);
            billingStateService.evaluateState(tenantId);
        }

        return ResponseEntity.ok().build();
    }

    // ==================== MANUAL ADJUSTMENTS ====================

    @PostMapping("/adjust/credit")
    @Operation(summary = "Manual credit")
    public ResponseEntity<Void> manualCredit(
            @PathVariable UUID tenantId,
            @Valid @RequestBody AdjustmentRequest request) {
        walletService.credit(tenantId, request.amount, "ADJUSTMENT:" + request.reason);
        billingStateService.evaluateState(tenantId);
        log.info("Manual credit for tenant {}: ₹{} - {}", tenantId, request.amount, request.reason);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/adjust/debit")
    @Operation(summary = "Manual debit")
    public ResponseEntity<Void> manualDebit(
            @PathVariable UUID tenantId,
            @Valid @RequestBody AdjustmentRequest request) {
        walletService.debit(tenantId, request.amount, "ADJUSTMENT:" + request.reason,request.subscriptionId);
        billingStateService.evaluateState(tenantId);
        log.info("Manual debit for tenant {}: ₹{} - {}", tenantId, request.amount, request.reason);
        return ResponseEntity.ok().build();
    }

    // ==================== REQUEST/RESPONSE CLASSES ====================

    // Wallet
    public record WalletResponse(UUID tenantId, BigDecimal balance, String currency, String status) {}
    public record WalletBalanceResponse(BigDecimal balance, String currency) {}

    // Charge
    @Getter
    public static class ChargeRequest {
        @NotNull @DecimalMin("0.01")
        private BigDecimal amount;
        @NotBlank
        private String type; // SUBSCRIPTION, USAGE, etc.
        private String description;
        private UUID subscriptionId;
        private Map<String, Object> metadata;
    }
    public record ChargeResponse(boolean success, BigDecimal newBalance, String message) {}

    // Recurring Charge
    @Getter
    public static class RecurringChargeRequest {
        @NotBlank
        private String type; // PLATFORM_FEE, DID_RENTAL, AGENT_FEE
        @NotNull @DecimalMin("0.01")
        private BigDecimal amount;
        private String frequency; // MONTHLY (default), WEEKLY
        private String sourceType; // DID, PLAN
        private UUID sourceId;
        private String description;
        private UUID subscriptionId; // Optional, for better charge tracking
    }
    public record RecurringChargeResponse(UUID id, String type, BigDecimal amount, LocalDate nextChargeDate) {}

    // DID Rental
    @Getter
    public static class DidRentalRequest {
        @NotNull
        private UUID didId;
        @NotBlank
        private String didNumber;
        @NotNull
        private BigDecimal amount;

        @NotNull
        private UUID subscriptionId; // Optional, for better charge tracking
    }

    // Usage
    @Getter
    public static class RecordUsageRequest {
        @NotNull
        private UsageMetric metric;
        @NotNull
        private BigDecimal quantity;
        @NotNull
        private BillingUnit unit;
        private BigDecimal unitCost;
        private BigDecimal totalCost;
        private String sourceType;
        private UUID sourceId;
        private String description;
        private UUID subscriptionId; // Optional, for better charge tracking


    }

    // Adjustment
    @Getter
    public static class AdjustmentRequest {
        @NotNull @DecimalMin("0.01")
        private BigDecimal amount;
        @NotBlank
        private String reason;

        private UUID subscriptionId; // Optional, for better charge tracking
    }

    // Billing State
    @Builder @Getter
    public static class BillingStateResponse {
        private UUID tenantId;
        private String state;
        private boolean canMakeCalls;
        private Instant graceExpiresAt;
        private String blockReason;
    }
}