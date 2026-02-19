package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.Transaction;
import com.dalai.llama.billing.repository.PaymentRepository;
import com.dalai.llama.billing.repository.RecurringChargeRepository;
import com.dalai.llama.billing.service.BillingStateService;
import com.dalai.llama.billing.service.TransactionService;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks/razorpay")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks", description = "Payment gateway webhook handlers")
@Hidden
public class RazorpayWebhookController {

    private final PaymentRepository paymentRepository;
    private final RecurringChargeRepository recurringChargeRepository;
    private final WalletService walletService;
    private final TransactionService transactionService;
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

        // Idempotency key
        String idempotencyKey = "CREDIT:PAYMENT:" + paymentId;

        if (transactionService.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Payment {} already processed, skipping", paymentId);
            return;
        }

        paymentRepository.findByGatewayOrderId(orderId).ifPresent(payment -> {
            UUID tenantId = payment.getTenantId();

            // 1. Mark payment success
            payment.markSuccess(paymentId, "webhook-verified");
            paymentRepository.save(payment);

            // 2. Credit wallet (creates Transaction with idempotencyKey)
            walletService.credit(
                    tenantId,
                    payment.getAmount(),
                    "PAYMENT:" + paymentId,
                    null,
                    idempotencyKey
            );

            // 3. Evaluate billing state
            billingStateService.evaluateState(tenantId);

            log.info("Payment captured - Tenant: {}, Amount: ₹{}", tenantId, payment.getAmount());
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
        String refundId = refundEntity.path("id").asText();
        int amountPaise = refundEntity.path("amount").asInt();
        BigDecimal amount = BigDecimal.valueOf(amountPaise).divide(BigDecimal.valueOf(100));

        log.info("Refund created - Payment: {}, RefundId: {}, Amount: ₹{}", paymentId, refundId, amount);

        // Idempotency key
        String idempotencyKey = "DEBIT:REFUND:" + refundId;

        if (transactionService.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Refund {} already processed, skipping", refundId);
            return;
        }

        Payment payment = paymentRepository.findByGatewayPaymentId(paymentId).orElse(null);
        if (payment == null) {
            log.warn("Payment not found for refund: {}", paymentId);
            return;
        }

        UUID tenantId = payment.getTenantId();

        // Find subscriptionId from original credit transaction
        String originalKey = "CREDIT:PAYMENT:" + paymentId;
        UUID subscriptionId = transactionService.findByIdempotencyKey(originalKey)
                .map(Transaction::getSubscriptionId)
                .orElse(null);

        // 1. Debit wallet (creates Transaction)
        walletService.debit(
                tenantId,
                amount,
                "REFUND:" + refundId,
                subscriptionId,
                idempotencyKey
        );

        // 2. Update payment status
        payment.markRefunded("Refund: " + refundId);
        paymentRepository.save(payment);

        // 3. Handle subscription refund
        if (subscriptionId != null) {
            handleSubscriptionRefund(tenantId, subscriptionId, refundId);
        }

        // 4. Evaluate billing state
        billingStateService.evaluateState(tenantId);

        log.info("Refund processed - Tenant: {}, Subscription: {}, Amount: ₹{}",
                tenantId, subscriptionId, amount);
    }

    private void handleSubscriptionRefund(UUID tenantId, UUID subscriptionId, String refundId) {
        log.info("Cancelling recurring charges for subscription: {}", subscriptionId);

        recurringChargeRepository.findBySubscriptionId(subscriptionId).forEach(charge -> {
            charge.cancel();
            recurringChargeRepository.save(charge);
            log.info("Cancelled recurring charge: {}", charge.getId());
        });

        // TODO: Notify product-service
        // productServiceClient.notifySubscriptionRefunded(tenantId, subscriptionId, refundId);
    }

    private void handleOrderPaid(JsonNode event) {
        JsonNode orderEntity = event.path("payload").path("order").path("entity");
        String orderId = orderEntity.path("id").asText();

        log.info("Order paid - Order: {}", orderId);

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