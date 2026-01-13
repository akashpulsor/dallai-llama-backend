package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.repository.PaymentRepository;
import com.dalai.llama.billing.service.BillingStateService;
import com.dalai.llama.billing.service.PaymentService;
import com.dalai.llama.billing.service.WalletService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/v1/webhooks/razorpay")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks", description = "Payment gateway webhook handlers")
@Hidden
public class RazorpayWebhookController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final WalletService walletService;
    private final BillingStateService billingStateService;
    private final ObjectMapper objectMapper;

    @Value("${razorpay.webhook-secret}")
    private String webhookSecret;

    @PostMapping
    @Operation(summary = "Razorpay webhook", description = "Handle Razorpay payment events")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-Razorpay-Signature") String signature,
            @RequestBody String payload
    ) {
        log.info("Received Razorpay webhook");

        // 1. Verify signature
        if (!verifySignature(payload, signature)) {
            log.warn("Invalid webhook signature");
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        try {
            JsonNode event = objectMapper.readTree(payload);
            String eventType = event.path("event").asText();

            log.info("Processing Razorpay event: {}", eventType);

            switch (eventType) {
                case "payment.captured" -> handlePaymentCaptured(event);
                case "payment.failed" -> handlePaymentFailed(event);
                case "refund.created" -> handleRefundCreated(event);
                case "order.paid" -> handleOrderPaid(event);
                default -> log.info("Unhandled event type: {}", eventType);
            }

            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            log.error("Error processing webhook", e);
            return ResponseEntity.internalServerError().body("Error processing webhook");
        }
    }

    private void handlePaymentCaptured(JsonNode event) {
        JsonNode paymentEntity = event.path("payload").path("payment").path("entity");

        String orderId = paymentEntity.path("order_id").asText();
        String paymentId = paymentEntity.path("id").asText();

        log.info("Payment captured - Order: {}, Payment: {}", orderId, paymentId);

        // The signature verification for the payment itself happens during client callback
        // Here we just update the payment status
        paymentRepository.findByGatewayOrderId(orderId).ifPresent(payment -> {
            if (payment.getGatewayPaymentId() == null) {
                // Payment not yet verified via client callback, mark as captured
                payment.markSuccess(paymentId, "webhook-verified");
                paymentRepository.save(payment);

                // Credit wallet
                walletService.credit(
                        payment.getTenantId(),
                        payment.getAmount(),
                        "PAYMENT:" + paymentId
                );

                // Evaluate billing state
                billingStateService.evaluateState(payment.getTenantId());

                log.info("Wallet credited via webhook for order: {}", orderId);
            }
        });
    }

    private void handlePaymentFailed(JsonNode event) {
        JsonNode paymentEntity = event.path("payload").path("payment").path("entity");

        String orderId = paymentEntity.path("order_id").asText();
        String errorCode = paymentEntity.path("error_code").asText();
        String errorDescription = paymentEntity.path("error_description").asText();

        log.info("Payment failed - Order: {}, Error: {} - {}", orderId, errorCode, errorDescription);

        paymentRepository.findByGatewayOrderId(orderId).ifPresent(payment -> {
            payment.markFailed(errorCode + ": " + errorDescription);
            paymentRepository.save(payment);
        });
    }

    private void handleRefundCreated(JsonNode event) {
        JsonNode refundEntity = event.path("payload").path("refund").path("entity");

        String paymentId = refundEntity.path("payment_id").asText();
        int amountPaise = refundEntity.path("amount").asInt();
        java.math.BigDecimal amount = java.math.BigDecimal.valueOf(amountPaise)
                .divide(java.math.BigDecimal.valueOf(100));

        log.info("Refund created - Payment: {}, Amount: {}", paymentId, amount);

        // Find payment by gateway payment ID
        paymentRepository.findAll().stream()
                .filter(p -> paymentId.equals(p.getGatewayPaymentId()))
                .findFirst()
                .ifPresent(payment -> {
                    // Debit wallet for refund
                    walletService.debit(
                            payment.getTenantId(),
                            amount,
                            "REFUND:" + refundEntity.path("id").asText()
                    );

                    // Update payment status
                    payment.markRefunded("Refund processed");
                    paymentRepository.save(payment);

                    // Evaluate billing state
                    billingStateService.evaluateState(payment.getTenantId());

                    log.info("Refund processed for payment: {}", paymentId);
                });
    }

    private void handleOrderPaid(JsonNode event) {
        // Alternative to payment.captured
        JsonNode orderEntity = event.path("payload").path("order").path("entity");
        String orderId = orderEntity.path("id").asText();

        log.info("Order paid - Order: {}", orderId);

        // Usually already handled by payment.captured
        paymentRepository.findByGatewayOrderId(orderId).ifPresent(payment -> {
            if (payment.getGatewayPaymentId() == null) {
                log.info("Order {} marked as paid but no payment ID yet", orderId);
            }
        });
    }

    private boolean verifySignature(String payload, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKeySpec);

            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = HexFormat.of().formatHex(hmacBytes);

            return expectedSignature.equals(signature);

        } catch (Exception e) {
            log.error("Error verifying webhook signature", e);
            return false;
        }
    }
}
