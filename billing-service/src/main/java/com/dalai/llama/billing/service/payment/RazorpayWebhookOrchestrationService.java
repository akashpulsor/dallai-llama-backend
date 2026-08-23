package com.dalai.llama.billing.service.payment;

import com.dalai.llama.billing.client.ProductServiceClient;
import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.PaymentEvent;
import com.dalai.llama.billing.domain.entity.Transaction;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.domain.event.SubscriptionActivatedEvent;
import com.dalai.llama.billing.domain.event.WalletCreditedEvent;
import com.dalai.llama.billing.kafka.producer.BillingEventProducer;
import com.dalai.llama.billing.repository.PaymentEventRepository;
import com.dalai.llama.billing.repository.PaymentRepository;
import com.dalai.llama.billing.repository.RecurringChargeRepository;
import com.dalai.llama.billing.service.BillingStateService;
import com.dalai.llama.billing.service.TransactionService;
import com.dalai.llama.billing.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RazorpayWebhookOrchestrationService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final RecurringChargeRepository recurringChargeRepository;
    private final WalletService walletService;
    private final TransactionService transactionService;
    private final BillingStateService billingStateService;
    private final RazorpayService razorpayService;
    private final ProductServiceClient productServiceClient;
    private final BillingEventProducer eventProducer;

    public void processWebhook(String eventType, String payload, String signature) {
        switch (eventType) {
            case "payment.captured" -> handlePaymentCaptured(payload);
            case "payment.failed"   -> handlePaymentFailed(payload);
            case "refund.created"   -> handleRefundCreated(payload);
            case "order.paid"       -> handleOrderPaid(payload);
            default -> log.info("Unhandled webhook event: {}", eventType);
        }
    }

    /* ================================================================
       payment.captured
       ================================================================ */
    @Transactional
    private void handlePaymentCaptured(String payload) {
        var entity = razorpayService.extractPaymentEntity(payload);

        String orderId          = entity.path("order_id").asText();
        String gatewayPaymentId = entity.path("id").asText();

        log.info("Payment captured: order={}, payment={}", orderId, gatewayPaymentId);

        // Idempotency
        String idempotencyKey = "CREDIT:PAYMENT:" + gatewayPaymentId;
        if (transactionService.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Already processed payment {}", gatewayPaymentId);
            return;
        }

        Payment payment = paymentRepository.findByGatewayOrderId(orderId).orElse(null);
        if (payment == null) {
            log.warn("No payment found for order {}", orderId);
            return;
        }

        UUID tenantId = payment.getTenantId();

        // 1. State transition + record event
        PaymentStatus previous = payment.markSuccess(gatewayPaymentId, "webhook-verified");
        paymentRepository.save(payment);
        recordEvent(payment, previous, PaymentStatus.SUCCESS, "Captured via webhook", "WEBHOOK");

        // 2. Extract subscription context
        UUID subscriptionId =  payment.getSubscriptionId();//  extractSubscriptionId(payment);

        walletService.credit(tenantId, payment.getAmount(),
                "PAYMENT:" + gatewayPaymentId, subscriptionId, idempotencyKey);
        billingStateService.evaluateState(tenantId);
        BigDecimal balance = walletService.getBalance(tenantId);
        WalletCreditedEvent walletEvent = WalletCreditedEvent.builder()
                .tenantId(tenantId)
                .walletId(payment.getWalletId())
                .paymentId(payment.getId())
                .amount(payment.getAmount())
                .gateway("PAYMENT:" + gatewayPaymentId)
                .subscriptionId(subscriptionId)
                .totalBalance(balance)
                .currency(payment.getCurrency())
                .amount(payment.getAmount())
                .occurredAt(Instant.now())
                .build();
        eventProducer.publishWalletFunded(walletEvent);

        // Payment.toEvent() carries projectRequirementId when this payment funded a specific
        // creative-planning-service brief -- this is the reliable (webhook-confirmed) trigger
        // consumers should key project-funded state off, not the client-callback verify path.
        eventProducer.publishPaymentReceived(payment.toEvent());
    }

    /**
     * Subscription payment: credit only wallet portion, activate subscription,
     * publish Kafka event for UI websocket.
     */

    /* ================================================================
       payment.failed
       ================================================================ */
    @Transactional
    private void handlePaymentFailed(String payload) {
        var entity = razorpayService.extractPaymentEntity(payload);

        String orderId          = entity.path("order_id").asText();
        String errorCode        = entity.path("error_code").asText();
        String errorDescription = entity.path("error_description").asText();
        String reason = errorCode + ": " + errorDescription;

        paymentRepository.findByGatewayOrderId(orderId).ifPresent(payment -> {
            try {
                PaymentStatus previous = payment.markFailed(reason);
                paymentRepository.save(payment);
                recordEvent(payment, previous, PaymentStatus.FAILED, reason, "WEBHOOK");
            } catch (IllegalStateException e) {
                log.warn("Cannot mark payment {} as FAILED: {}", payment.getId(), e.getMessage());
            }
        });
    }

    /* ================================================================
       refund.created
       ================================================================ */
    @Transactional
    private void handleRefundCreated(String payload) {
        var refundEntity = razorpayService.extractRefundEntity(payload);

        String paymentId = refundEntity.path("payment_id").asText();
        String refundId  = refundEntity.path("id").asText();
        BigDecimal amount = BigDecimal.valueOf(refundEntity.path("amount").asInt())
                .divide(BigDecimal.valueOf(100));

        // Idempotency
        String idempotencyKey = "DEBIT:REFUND:" + refundId;
        if (transactionService.existsByIdempotencyKey(idempotencyKey)) return;

        Payment payment = paymentRepository.findByGatewayPaymentId(paymentId).orElse(null);
        if (payment == null) return;

        UUID tenantId = payment.getTenantId();

        // State transition + event
        try {
            PaymentStatus previous = payment.markRefunded("Refund: " + refundId);
            paymentRepository.save(payment);
            recordEvent(payment, previous, PaymentStatus.REFUNDED, "Refund " + refundId, "WEBHOOK");
        } catch (IllegalStateException e) {
            log.warn("Cannot refund payment {}: {}", payment.getId(), e.getMessage());
            return;
        }

        // Debit wallet
        String originalKey = "CREDIT:PAYMENT:" + paymentId;
        UUID subscriptionId = transactionService.findByIdempotencyKey(originalKey)
                .map(Transaction::getSubscriptionId)
                .orElse(null);

        walletService.debit(tenantId, amount, "REFUND:" + refundId,
                subscriptionId, idempotencyKey);

        // Cancel recurring charges tied to this subscription
        if (subscriptionId != null) {
            recurringChargeRepository.cancelBySubscriptionId(subscriptionId);
        }

        billingStateService.evaluateState(tenantId);
    }

    /* ================================================================
       order.paid — reconciliation checkpoint
       Fires AFTER payment.captured for the same order.
       No wallet action (already done in payment.captured),
       but record as audit trail for payment journey.
       ================================================================ */
    private void handleOrderPaid(String payload) {
        var paymentEntity = razorpayService.extractPaymentEntity(payload);
        String orderId = paymentEntity.path("order_id").asText();

        paymentRepository.findByGatewayOrderId(orderId).ifPresent(payment -> {
            // Only record if payment was already captured (normal flow)
            if (payment.getStatus() == PaymentStatus.SUCCESS) {
                recordEvent(payment, PaymentStatus.SUCCESS, PaymentStatus.SUCCESS,
                        "Order fully paid (reconciliation)", "WEBHOOK");
                log.info("Order paid recorded for payment {}", payment.getId());
            } else {
                log.warn("order.paid received but payment {} is in {} state",
                        payment.getId(), payment.getStatus());
            }
        });
    }

    /* ================================================================
       Subscription activation with retry + Kafka publish
       ================================================================ */
    private void processSubscriptionActivation(Payment payment, UUID subscriptionId) {
        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                productServiceClient.postSubscription(subscriptionId);
                log.info("Subscription activated: {}", subscriptionId);

                // Publish to Kafka → UI picks up via STOMP websocket
                eventProducer.publishSubscriptionActivated(
                        SubscriptionActivatedEvent.builder()
                                .tenantId(payment.getTenantId())
                                .subscriptionId(subscriptionId)
                                .paymentId(payment.getId())
                                .planAmount(extractAmount(payment, "PLAN_AMOUNT:"))
                                .walletCredit(extractAmount(payment, "WALLET_CREDIT:"))
                                .occurredAt(Instant.now())
                                .build()
                );
                return;

            } catch (Exception e) {
                log.error("Activation attempt {} failed for {}", attempt, subscriptionId, e);

                if (attempt == maxRetries) {
                    log.error("Permanent failure — initiating refund for {}", subscriptionId);
                    razorpayService.initiateRefund(
                            payment.getGatewayPaymentId(), payment.getAmount());
                }

                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /* ================================================================
       Shared helpers (DRY)
       ================================================================ */

    /** Record a PaymentEvent — used by all handlers */
    private void recordEvent(Payment payment, PaymentStatus from,
                             PaymentStatus to, String reason, String source) {
        paymentEventRepository.save(PaymentEvent.record(payment, from, to, reason, source));
    }

    /** Extract a BigDecimal value from payment description by marker.
     *  Format: SUBSCRIPTION:CODE|SUBSCRIPTION_ID:uuid|PLAN_AMOUNT:123|WALLET_CREDIT:456
     */
    private BigDecimal extractAmount(Payment payment, String marker) {
        try {
            String desc = payment.getDescription();
            if (desc == null) return null;
            int start = desc.indexOf(marker);
            if (start == -1) return null;
            start += marker.length();
            int end = desc.indexOf("|", start);
            String val = end == -1 ? desc.substring(start) : desc.substring(start, end);
            return new BigDecimal(val);
        } catch (Exception e) {
            log.warn("Cannot extract {} from payment {}", marker, payment.getId());
            return null;
        }
    }

    private UUID extractSubscriptionId(Payment payment) {
        try {
            String desc = payment.getDescription();
            if (desc == null) return null;

            // First check entity field
            if (payment.getSubscriptionId() != null) return payment.getSubscriptionId();

            // Fallback: parse from description
            String marker = "SUBSCRIPTION_ID:";
            int start = desc.indexOf(marker);
            if (start == -1) return null;
            start += marker.length();
            int end = desc.indexOf("|", start);
            String id = end == -1 ? desc.substring(start) : desc.substring(start, end);
            return UUID.fromString(id);
        } catch (Exception e) {
            log.warn("Unable to extract subscription id from payment {}", payment.getId());
            return null;
        }
    }
}