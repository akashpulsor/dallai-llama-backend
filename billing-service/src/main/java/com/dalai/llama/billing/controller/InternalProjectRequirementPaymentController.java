package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.PaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The public brief page's (creative-planning-service's {@code PublicProjectRequirementController})
 * only way to charge a real Razorpay payment: the visitor holding a share-token link has no
 * account/JWT here, so {@link PaymentController}'s tenant-authenticated {@code /api/v1/billing/**}
 * surface is unreachable for them. Same internal, no-JWT convention as {@link
 * InternalVideoPricingController} -- thin pass-through to the exact same {@link PaymentService}
 * methods the authenticated dashboard already uses, no separate ledger or payment model.
 */
@Validated
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/project-requirement-payments")
@RequiredArgsConstructor
@Hidden
public class InternalProjectRequirementPaymentController {

    private final PaymentService paymentService;

    @PostMapping("/{requirementId}/order")
    public ResponseEntity<PaymentService.PaymentOrderResult> createOrder(
            @PathVariable UUID tenantId, @PathVariable UUID requirementId, @RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(paymentService.createProjectRequirementPaymentOrder(
                tenantId, requirementId, request.currency(), request.amount(), request.description()));
    }

    @PostMapping("/{paymentId}/verify")
    public ResponseEntity<Void> verify(
            @PathVariable UUID tenantId, @PathVariable UUID paymentId, @RequestBody VerifyRequest request) {
        paymentService.handlePaymentSuccess(
                tenantId, paymentId, request.gatewayOrderId(), request.gatewayPaymentId(), request.gatewaySignature());
        return ResponseEntity.noContent().build();
    }

    public record CreateOrderRequest(@NotBlank String currency, BigDecimal amount, String description) {}

    public record VerifyRequest(@NotBlank String gatewayOrderId, @NotBlank String gatewayPaymentId, @NotBlank String gatewaySignature) {}
}
