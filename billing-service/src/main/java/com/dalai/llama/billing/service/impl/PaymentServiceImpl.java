package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.PaymentEvent;
import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.domain.exception.PaymentFailedException;
import com.dalai.llama.billing.domain.exception.WalletNotFoundException;
import com.dalai.llama.billing.kafka.producer.BillingEventProducer;
import com.dalai.llama.billing.repository.PaymentEventRepository;
import com.dalai.llama.billing.repository.PaymentRepository;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.service.PaymentService;
import com.dalai.llama.billing.service.WalletService;
import com.dalai.llama.billing.service.payment.PaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final WalletRepository walletRepository;
    private final PaymentGateway paymentGateway;
    private final WalletService walletService;
    private final BillingEventProducer eventProducer;

    @Override
    @Transactional
    public UUID createPayment(UUID tenantId, BigDecimal amount, String description) {

        log.info("Creating payment for tenant={} amount={} description={}",
                tenantId, amount, description);
        // 1. Load wallet (currency source of truth)
        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        // 2. Create gateway order
        final String gatewayOrderId;
        try {
            gatewayOrderId = paymentGateway.createOrder(
                    amount, wallet.getCurrency(), "rcpt_" + tenantId);
        } catch (Exception e) {
            throw new PaymentFailedException("Failed to create payment order", e);
        }

        // 3. Create payment entity
        Payment payment = Payment.create(
                tenantId, wallet.getId(), amount,
                wallet.getCurrency(), "RAZORPAY",
                gatewayOrderId, null,description
        );

        paymentRepository.save(payment);

        // 4. Record creation event in payment journey
        paymentEventRepository.save(PaymentEvent.record(
                payment, null, PaymentStatus.PENDING,
                description, "SYSTEM"
        ));

        return payment.getId();
    }

    @Override
    public UUID createPayment(UUID tenantId, String currency, BigDecimal amount, String description, UUID subscriptionId) {
        log.info("Creating payment for tenant={} amount={}  currency={} description={}, subscriptionId={}",
                tenantId, amount, currency,description, subscriptionId);
        // 1. Load wallet (currency source of truth)
        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        // 2. Create gateway order
        final String gatewayOrderId;
        try {
            gatewayOrderId = paymentGateway.createOrder(
                    amount, wallet.getCurrency(), "rcpt_" + tenantId);
        } catch (Exception e) {
            throw new PaymentFailedException("Failed to create payment order", e);
        }

        // 3. Create payment entity
        Payment payment = Payment.create(
                tenantId, wallet.getId(), amount,
                wallet.getCurrency(), "RAZORPAY",
                gatewayOrderId, subscriptionId,description
        );

        paymentRepository.save(payment);

        // 4. Record creation event in payment journey
        paymentEventRepository.save(PaymentEvent.record(
                payment, null, PaymentStatus.PENDING,
                description, "SYSTEM"
        ));

        return payment.getId();

    }

    @Override
    @Transactional
    public void handlePaymentSuccess(String gatewayOrderId, String paymentId, String signature) {

        Payment payment = paymentRepository.findByGatewayOrderId(gatewayOrderId)
                .orElseThrow(() ->
                        new PaymentFailedException("Payment not found for order " + gatewayOrderId));

        // 1. State transition + event
        PaymentStatus previous = payment.markSuccess(paymentId, signature);
        paymentRepository.save(payment);
        paymentEventRepository.save(PaymentEvent.record(
                payment, previous, PaymentStatus.SUCCESS,
                "Verified via client callback", "USER"
        ));

        // 2. Credit wallet
        walletService.credit(
                payment.getTenantId(),
                payment.getAmount(),
                "PAYMENT:" + paymentId
        );

        // 3. Publish event
        eventProducer.publishPaymentReceived(payment.toEvent());
    }

    @Override
    public SubscriptionPaymentResult createSubscriptionPayment(
            UUID tenantId,
            String planCode,
            BigDecimal planAmount,
            BigDecimal walletCredit,
            UUID subscriptionId
    ) {
        BigDecimal totalAmount = planAmount.add(walletCredit);

        String description =
                "SUBSCRIPTION:" + planCode +
                        "|SUBSCRIPTION_ID:" + subscriptionId +
                        "|PLAN_AMOUNT:" + planAmount +
                        "|WALLET_CREDIT:" + walletCredit;

        UUID paymentId = createPayment(tenantId, totalAmount, description);

        // Link subscription to payment for direct lookup
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        payment.linkSubscription(subscriptionId);
        paymentRepository.save(payment);

        return new SubscriptionPaymentResult(
                payment.getId(),
                payment.getGatewayOrderId(),
                totalAmount,
                planAmount,
                walletCredit,
                payment.getCurrency()
        );
    }
}