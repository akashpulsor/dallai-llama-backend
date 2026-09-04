package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.ClientReviewPaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Internal (service-mesh, no JWT) surface for the client pay-to-lock flow. Called by
 * pre-production-service's token-scoped public controller -- the client never hits billing
 * directly. Same {@code /api/v1/internal/tenants/{tenantId}/...} convention as the rest of
 * billing's internal API. */
@Hidden
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/client-review")
@RequiredArgsConstructor
public class ClientReviewPaymentController {

    private final ClientReviewPaymentService service;

    @PostMapping("/{projectId}/quote")
    public ResponseEntity<ClientReviewPaymentService.Quote> quote(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(service.quote(tenantId, projectId));
    }

    @PostMapping("/{projectId}/order")
    public ResponseEntity<ClientReviewPaymentService.OrderResult> createOrder(
            @PathVariable UUID tenantId, @PathVariable UUID projectId, @RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(service.createOrder(tenantId, projectId, request.reviewToken()));
    }

    @PostMapping("/verify")
    public ResponseEntity<ClientReviewPaymentService.VerifyResult> verify(
            @PathVariable UUID tenantId, @RequestBody VerifyRequest request) {
        return ResponseEntity.ok(service.verify(request.gatewayOrderId(), request.gatewayPaymentId(), request.gatewaySignature()));
    }

    public record CreateOrderRequest(String reviewToken) {}

    public record VerifyRequest(String gatewayOrderId, String gatewayPaymentId, String gatewaySignature) {}
}
