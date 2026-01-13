package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.exception.PaymentFailedException;
import com.dalai.llama.billing.domain.exception.WalletNotFoundException;
import com.dalai.llama.billing.kafka.producer.BillingEventProducer;
import com.dalai.llama.billing.repository.PaymentRepository;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.service.PaymentService;
import com.dalai.llama.billing.service.WalletService;
import com.dalai.llama.billing.service.payment.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final PaymentGateway paymentGateway;
    private final WalletService walletService;
    private final BillingEventProducer eventProducer;

    @Override
    @Transactional
    public UUID createPayment(UUID tenantId, BigDecimal amount, String description) {

        // 1. Load wallet (currency source of truth)
        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        // 2. Create gateway order (currency-aware)
        final String gatewayOrderId;
        try {
            gatewayOrderId = paymentGateway.createOrder(
                    amount,
                    wallet.getCurrency(),
                    "rcpt_" + tenantId
            );
        } catch (Exception e) {
            throw new PaymentFailedException("Failed to create payment order", e);
        }

        // 3. Create payment domain entity
        Payment payment = Payment.create(
                tenantId,
                wallet.getId(),
                amount,
                wallet.getCurrency(),
                "RAZORPAY",
                gatewayOrderId,
                description
        );

        paymentRepository.save(payment);
        return payment.getId();
    }

    @Override
    @Transactional
    public void handlePaymentSuccess(String gatewayOrderId, String paymentId, String signature) {

        Payment payment = paymentRepository.findByGatewayOrderId(gatewayOrderId)
                .orElseThrow(() ->
                        new PaymentFailedException("Payment not found for order " + gatewayOrderId)
                );

        // 1. Update payment state
        payment.markSuccess(paymentId, signature);
        paymentRepository.save(payment);

        // 2. Credit wallet
        walletService.credit(
                payment.getTenantId(),
                payment.getAmount(),
                "PAYMENT:" + paymentId
        );

        // 3. Publish event
        eventProducer.publishPaymentReceived(payment.toEvent());
    }
}
