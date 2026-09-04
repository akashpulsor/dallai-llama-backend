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

/** Internal surface for the extra-review paywall (a review beyond a project's included allowance).
 * Same convention as {@link ClientReviewPaymentController}; verification reuses that flow's
 * {@code /client-review/verify} endpoint (a payment is a payment -- only the price differs). */
@Hidden
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/extra-review")
@RequiredArgsConstructor
public class ExtraReviewPaymentController {

    private final ClientReviewPaymentService service;

    @PostMapping("/{projectId}/quote")
    public ResponseEntity<ClientReviewPaymentService.Quote> quote(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(service.extraReviewQuote(tenantId, projectId));
    }

    @PostMapping("/{projectId}/order")
    public ResponseEntity<ClientReviewPaymentService.OrderResult> createOrder(
            @PathVariable UUID tenantId, @PathVariable UUID projectId, @RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(service.createExtraReviewOrder(tenantId, projectId, request.reviewToken()));
    }

    public record CreateOrderRequest(String reviewToken) {}
}
