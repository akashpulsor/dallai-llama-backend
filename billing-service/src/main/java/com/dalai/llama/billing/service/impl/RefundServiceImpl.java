package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.PaymentEvent;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.domain.event.RefundInitiatedEvent;
import com.dalai.llama.billing.kafka.producer.BillingEventProducer;
import com.dalai.llama.billing.repository.PaymentEventRepository;
import com.dalai.llama.billing.repository.PaymentRepository;
import com.dalai.llama.billing.service.RefundService;
import com.dalai.llama.billing.service.payment.RazorpayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final RazorpayService razorpayService;
    private final BillingEventProducer eventProducer;

    @Override
    @Transactional
    public String initiateRefund(UUID paymentId, BigDecimal amount, String reason, String trigger) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new IllegalStateException(
                    "Cannot refund payment " + paymentId + " in " + payment.getStatus() + " state");
        }

        if (payment.getGatewayPaymentId() == null) {
            throw new IllegalStateException(
                    "No gateway payment ID on payment " + paymentId + " — cannot initiate refund");
        }

        BigDecimal refundAmount = (amount != null) ? amount : payment.getAmount();

        if (refundAmount.compareTo(payment.getAmount()) > 0) {
            throw new IllegalArgumentException(
                    "Refund amount " + refundAmount + " exceeds payment amount " + payment.getAmount());
        }

        String refundId = razorpayService.initiateRefund(payment.getGatewayPaymentId(), refundAmount);

        paymentEventRepository.save(PaymentEvent.record(
                payment, PaymentStatus.SUCCESS, PaymentStatus.SUCCESS,
                "Refund initiated: " + refundId + (reason != null ? " — " + reason : ""),
                trigger
        ));

        log.info("Refund initiated: payment={}, refund={}, amount={}, trigger={}",
                paymentId, refundId, refundAmount, trigger);

        eventProducer.publishRefundInitiated(RefundInitiatedEvent.builder()
                .tenantId(payment.getTenantId())
                .paymentId(paymentId)
                .subscriptionId(payment.getSubscriptionId())
                .amount(refundAmount)
                .refundId(refundId)
                .reason(reason)
                .trigger(trigger)
                .occurredAt(Instant.now())
                .build());

        return refundId;
    }
}
