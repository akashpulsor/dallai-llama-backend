package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.PaymentMethod;
import com.dalai.llama.billing.dto.request.AddPaymentMethodRequest;
import com.dalai.llama.billing.dto.request.CreatePaymentRequest;
import com.dalai.llama.billing.dto.request.VerifyPaymentRequest;
import com.dalai.llama.billing.dto.response.PaymentMethodResponse;
import com.dalai.llama.billing.dto.response.PaymentResponse;
import com.dalai.llama.billing.repository.PaymentMethodRepository;
import com.dalai.llama.billing.repository.PaymentRepository;
import com.dalai.llama.billing.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment processing APIs")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final PaymentMethodRepository paymentMethodRepository;

    // ==================== PAYMENTS ====================

    @PostMapping("/payments")
    @Operation(summary = "Create payment order", description = "Create a new payment order for wallet recharge")
    public ResponseEntity<CreatePaymentResponse> createPayment(
            @PathVariable UUID tenantId,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        UUID paymentId = paymentService.createPayment(
                tenantId,
                request.getAmount(),
                request.getDescription()
        );

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();

        return ResponseEntity.ok(CreatePaymentResponse.builder()
                .paymentId(paymentId)
                .gatewayOrderId(payment.getGatewayOrderId())
                .amount(request.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus().name())
                .build());
    }

    @GetMapping("/payments")
    @Operation(summary = "List payments", description = "Get list of payments for a tenant")
    public ResponseEntity<List<PaymentResponse>> listPayments(@PathVariable UUID tenantId) {
        List<Payment> payments = paymentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);

        List<PaymentResponse> responses = payments.stream()
                .map(p -> PaymentResponse.builder()
                        .paymentId(p.getId())
                        .amount(p.getAmount())
                        .currency(p.getCurrency())
                        .status(p.getStatus().name())
                        .gateway(p.getGateway())
                        .gatewayOrderId(p.getGatewayOrderId())
                        .gatewayPaymentId(p.getGatewayPaymentId())
                        .description(p.getDescription())
                        .createdAt(p.getCreatedAt())
                        .build())
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(summary = "Get payment details", description = "Retrieve specific payment details")
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable UUID tenantId,
            @PathVariable UUID paymentId
    ) {
        Payment payment = paymentRepository.findById(paymentId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));

        return ResponseEntity.ok(PaymentResponse.builder()
                .paymentId(payment.getId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus().name())
                .gateway(payment.getGateway())
                .gatewayOrderId(payment.getGatewayOrderId())
                .gatewayPaymentId(payment.getGatewayPaymentId())
                .failureReason(payment.getFailureReason())
                .description(payment.getDescription())
                .createdAt(payment.getCreatedAt())
                .build());
    }

    @PostMapping("/payments/{paymentId}/verify")
    @Operation(summary = "Verify payment", description = "Verify payment completion from client callback")
    public ResponseEntity<PaymentVerifyResponse> verifyPayment(
            @PathVariable UUID tenantId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody VerifyPaymentRequest request
    ) {
        paymentService.handlePaymentSuccess(
                request.getGatewayOrderId(),
                request.getGatewayPaymentId(),
                request.getGatewaySignature()
        );

        return ResponseEntity.ok(PaymentVerifyResponse.builder()
                .paymentId(paymentId)
                .status("SUCCESS")
                .message("Payment verified and wallet credited")
                .build());
    }

    // ==================== PAYMENT METHODS ====================

    @GetMapping("/payment-methods")
    @Operation(summary = "List payment methods", description = "Get saved payment methods for a tenant")
    public ResponseEntity<List<PaymentMethodResponse>> listPaymentMethods(@PathVariable UUID tenantId) {
        List<PaymentMethod> methods = paymentMethodRepository.findByTenantIdAndActiveTrue(tenantId);

        List<PaymentMethodResponse> responses = methods.stream()
                .map(m -> PaymentMethodResponse.builder()
                        .id(m.getId())
                        .type(m.getType().name())
                        .displayName(m.getDisplayName())
                        .maskedReference(m.getMaskedReference())
                        .isDefault(m.isDefault())
                        .expiresAt(m.getExpiresAt())
                        .build())
                .toList();

        return ResponseEntity.ok(responses);
    }

    @PostMapping("/payment-methods")
    @Operation(summary = "Add payment method", description = "Add a new saved payment method")
    public ResponseEntity<PaymentMethodResponse> addPaymentMethod(
            @PathVariable UUID tenantId,
            @Valid @RequestBody AddPaymentMethodRequest request
    ) {
        Instant now = Instant.now();

        PaymentMethod method = PaymentMethod.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .type(request.getType())
                .displayName(request.getDisplayName())
                .maskedReference(request.getMaskedReference())
                .isDefault(false)
                .active(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        paymentMethodRepository.save(method);

        return ResponseEntity.ok(PaymentMethodResponse.builder()
                .id(method.getId())
                .type(method.getType().name())
                .displayName(method.getDisplayName())
                .maskedReference(method.getMaskedReference())
                .isDefault(method.isDefault())
                .build());
    }

    @DeleteMapping("/payment-methods/{methodId}")
    @Operation(summary = "Remove payment method", description = "Deactivate a saved payment method")
    public ResponseEntity<Void> removePaymentMethod(
            @PathVariable UUID tenantId,
            @PathVariable UUID methodId
    ) {
        PaymentMethod method = paymentMethodRepository.findById(methodId)
                .filter(m -> m.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Payment method not found"));

        method.setActive(false);
        method.setUpdatedAt(Instant.now());
        paymentMethodRepository.save(method);

        return ResponseEntity.noContent().build();
    }

    @PutMapping("/payment-methods/{methodId}/default")
    @Operation(summary = "Set default payment method", description = "Set a payment method as the default")
    public ResponseEntity<PaymentMethodResponse> setDefaultPaymentMethod(
            @PathVariable UUID tenantId,
            @PathVariable UUID methodId
    ) {
        // Clear existing default
        paymentMethodRepository.findByTenantIdAndActiveTrue(tenantId)
                .forEach(m -> {
                    if (m.isDefault()) {
                        m.setDefault(false);
                        paymentMethodRepository.save(m);
                    }
                });

        // Set new default
        PaymentMethod method = paymentMethodRepository.findById(methodId)
                .filter(m -> m.getTenantId().equals(tenantId) && m.isActive())
                .orElseThrow(() -> new IllegalArgumentException("Payment method not found"));

        method.setDefault(true);
        method.setUpdatedAt(Instant.now());
        paymentMethodRepository.save(method);

        return ResponseEntity.ok(PaymentMethodResponse.builder()
                .id(method.getId())
                .type(method.getType().name())
                .displayName(method.getDisplayName())
                .maskedReference(method.getMaskedReference())
                .isDefault(true)
                .build());
    }

    // ==================== RESPONSE CLASSES ====================

    @lombok.Builder
    @lombok.Getter
    public static class CreatePaymentResponse {
        private UUID paymentId;
        private String gatewayOrderId;
        private java.math.BigDecimal amount;
        private String currency;
        private String status;
    }

    @lombok.Builder
    @lombok.Getter
    public static class PaymentVerifyResponse {
        private UUID paymentId;
        private String status;
        private String message;
    }
}
