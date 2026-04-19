package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.PaymentEvent;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.repository.PaymentEventRepository;
import com.dalai.llama.billing.repository.PaymentRepository;
import com.dalai.llama.billing.service.payment.RazorpayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Internal-only refund initiation.
 * Actual wallet debit happens automatically via refund.created webhook (idempotent).
 *
 * Path: /api/v1/internal/tenants/{tenantId}/refund/{paymentId}
 * Security: permitAll (service-mesh / internal network only)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/refund")
@RequiredArgsConstructor
@Tag(name = "Refund", description = "Internal refund initiation APIs")
public class RefundController {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final RazorpayService razorpayService;

    @PostMapping("/{paymentId}")
    @Operation(summary = "Initiate refund",
            description = "Trigger gateway refund for a captured payment. " +
                    "Wallet debit happens via webhook automatically.")
    public ResponseEntity<RefundResponse> initiateRefund(
            @PathVariable UUID tenantId,
            @PathVariable UUID paymentId,
            @RequestBody(required = false) RefundRequest request) {

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));

        // Guard 1: tenant ownership (prevent cross-tenant refund)
        if (!payment.getTenantId().equals(tenantId)) {
            log.warn("Refund blocked: payment {} does not belong to tenant {}", paymentId, tenantId);
            return ResponseEntity.notFound().build();
        }

        // Guard 2: payment must be SUCCESS
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            return ResponseEntity.badRequest().body(RefundResponse.builder()
                    .success(false)
                    .message("Cannot refund payment in " + payment.getStatus() + " state")
                    .build());
        }

        // Guard 3: gateway payment ID must exist
        if (payment.getGatewayPaymentId() == null) {
            return ResponseEntity.badRequest().body(RefundResponse.builder()
                    .success(false)
                    .message("No gateway payment ID — cannot initiate refund")
                    .build());
        }

        // Guard 4: within refund window (7 days from capture)
        if (payment.getUpdatedAt() != null &&
                payment.getUpdatedAt().isBefore(Instant.now().minus(7, ChronoUnit.DAYS))) {
            return ResponseEntity.badRequest().body(RefundResponse.builder()
                    .success(false)
                    .message("Refund window expired (>7 days since capture)")
                    .build());
        }

        // Determine refund amount
        BigDecimal refundAmount = (request != null && request.amount != null)
                ? request.amount : payment.getAmount();

        // Guard 5: amount cannot exceed payment
        if (refundAmount.compareTo(payment.getAmount()) > 0) {
            return ResponseEntity.badRequest().body(RefundResponse.builder()
                    .success(false)
                    .message("Refund amount exceeds payment amount")
                    .build());
        }

        try {
            String refundId = razorpayService.initiateRefund(
                    payment.getGatewayPaymentId(), refundAmount);

            // Record initiation event (state change happens via webhook)
            paymentEventRepository.save(PaymentEvent.record(
                    payment, PaymentStatus.SUCCESS, PaymentStatus.SUCCESS,
                    "Refund initiated: " + refundId +
                            (request != null && request.reason != null
                                    ? " — " + request.reason : ""),
                    "SYSTEM"
            ));

            log.info("Refund initiated: payment={}, refund={}, amount={}",
                    paymentId, refundId, refundAmount);

            return ResponseEntity.ok(RefundResponse.builder()
                    .success(true)
                    .refundId(refundId)
                    .amount(refundAmount)
                    .message("Refund initiated. Wallet will be debited on confirmation.")
                    .build());

        } catch (Exception e) {
            log.error("Failed to initiate refund for payment {}", paymentId, e);
            return ResponseEntity.internalServerError().body(RefundResponse.builder()
                    .success(false)
                    .message("Failed to initiate refund: " + e.getMessage())
                    .build());
        }
    }

    @Getter
    public static class RefundRequest {
        private BigDecimal amount; // null = full refund
        private String reason;
    }

    @Builder
    @Getter
    public static class RefundResponse {
        private boolean success;
        private String refundId;
        private BigDecimal amount;
        private String message;
    }
}