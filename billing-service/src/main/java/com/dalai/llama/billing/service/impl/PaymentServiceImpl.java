package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.PaymentEvent;
import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.domain.event.WalletDeductedForSubscriptionEvent;
import com.dalai.llama.billing.domain.exception.InsufficientBalanceException;
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
import java.time.Instant;
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

    // =========================
    // CREATE PAYMENT
    // =========================
    @Override
    @Transactional
    public UUID createPayment(UUID tenantId, BigDecimal amount, String description) {

        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        String gatewayOrderId;
        try {
            gatewayOrderId = paymentGateway.createOrder(
                    amount, wallet.getCurrency(), "rcpt_" + tenantId);
        } catch (Exception e) {
            throw new PaymentFailedException("Failed to create payment order", e);
        }

        Payment payment = Payment.create(
                tenantId, wallet.getId(), amount,
                wallet.getCurrency(), "RAZORPAY",
                gatewayOrderId, null, description
        );

        paymentRepository.save(payment);

        paymentEventRepository.save(PaymentEvent.record(
                payment,
                null,
                PaymentStatus.PENDING,
                description,
                "SYSTEM"
        ));

        return payment.getId();
    }

    @Override
    public UUID createPayment(UUID tenantId, String currency, BigDecimal amount,
                              String description, UUID subscriptionId) {

        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        String gatewayOrderId;
        try {
            gatewayOrderId = paymentGateway.createOrder(
                    amount, wallet.getCurrency(), "rcpt_" + tenantId);
        } catch (Exception e) {
            throw new PaymentFailedException("Failed to create payment order", e);
        }

        Payment payment = Payment.create(
                tenantId, wallet.getId(), amount,
                wallet.getCurrency(), "RAZORPAY",
                gatewayOrderId, subscriptionId, description
        );

        paymentRepository.save(payment);

        paymentEventRepository.save(PaymentEvent.record(
                payment,
                null,
                PaymentStatus.PENDING,
                description,
                "SYSTEM"
        ));

        return payment.getId();
    }

    // =========================
    // PAYMENT SUCCESS
    // =========================
    @Override
    @Transactional
    public void handlePaymentSuccess(String gatewayOrderId, String paymentId, String signature) {

        Payment payment = paymentRepository.findByGatewayOrderId(gatewayOrderId)
                .orElseThrow(() ->
                        new PaymentFailedException("Payment not found for order " + gatewayOrderId));

        PaymentStatus previous = payment.markSuccess(paymentId, signature);
        paymentRepository.save(payment);

        paymentEventRepository.save(PaymentEvent.record(
                payment,
                previous,
                PaymentStatus.SUCCESS,
                "Verified via client callback",
                "USER"
        ));

        walletService.credit(
                payment.getTenantId(),
                payment.getAmount(),
                "PAYMENT:" + paymentId
        );

        eventProducer.publishPaymentReceived(payment.toEvent());
    }

    // =========================
    // SUBSCRIPTION PAYMENT (WALLET)
    // =========================
    @Override
    @Transactional
    public SubscriptionPaymentResult createSubscriptionPayment(
            UUID tenantId,
            String planCode,
            BigDecimal planAmount,
            BigDecimal walletCredit,
            UUID subscriptionId
    ) {

        BigDecimal totalAmount = planAmount.add(walletCredit);

        Wallet wallet = walletRepository.findByTenantIdForUpdate(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet not found for tenant: " + tenantId));

        if (!wallet.hasSufficientBalance(totalAmount)) {
            throw new InsufficientBalanceException(
                    wallet.getBalance(), totalAmount);
        }

        wallet.debit(totalAmount);
        walletRepository.save(wallet);

        String description =
                "SUBSCRIPTION:" + planCode +
                        "|SUBSCRIPTION_ID:" + subscriptionId;

        UUID paymentId = UUID.randomUUID();

        Payment payment = Payment.builder()
                .id(paymentId)
                .tenantId(tenantId)
                .amount(totalAmount)
                .currency(wallet.getCurrency())
                .status(PaymentStatus.SUCCESS)
                .gateway("WALLET")
                .gatewayOrderId("WALLET_" + paymentId)
                .description(description)
                .createdAt(Instant.now())
                .build();

        payment.linkSubscription(subscriptionId);
        paymentRepository.save(payment);

        // ✅ FIXED EVENT
        paymentEventRepository.save(PaymentEvent.record(
                payment,
                null,
                PaymentStatus.SUCCESS,
                "SUBSCRIPTION_PAYMENT",
                "SYSTEM"
        ));

        // publish saga event
        WalletDeductedForSubscriptionEvent event = new WalletDeductedForSubscriptionEvent(
                UUID.randomUUID(),
                subscriptionId,
                tenantId,
                paymentId,
                planAmount,
                walletCredit,
                totalAmount,
                planCode,
                Instant.now()
        );

        eventProducer.publishWalletDebited(event);

        return new SubscriptionPaymentResult(
                payment.getId(),
                payment.getGatewayOrderId(),
                totalAmount,
                planAmount,
                walletCredit,
                payment.getCurrency()
        );
    }

    // =========================
    // REFUND
    // =========================
    @Transactional
    public void refundSubscriptionPayment(UUID tenantId, UUID paymentId,
                                          UUID subscriptionId, String reason) {

        if (paymentId == null) return;

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));

        if (payment.getStatus() == PaymentStatus.REFUNDED) return;
        if (payment.getStatus() != PaymentStatus.SUCCESS) return;

        Wallet wallet = walletRepository.findByTenantIdForUpdate(tenantId)
                .orElseThrow(() -> new IllegalStateException("Wallet not found: " + tenantId));

        wallet.credit(payment.getAmount());
        walletRepository.save(wallet);

        // ✅ FIX: update state properly
        PaymentStatus previous = payment.markRefunded(reason);
        paymentRepository.save(payment);

        // ✅ FIX: correct transition
        paymentEventRepository.save(PaymentEvent.record(
                payment,
                previous,
                PaymentStatus.REFUNDED,
                "REFUND:" + reason,
                "SYSTEM"
        ));

        log.info("Refund completed: paymentId={}, amount={}",
                paymentId, payment.getAmount());
    }
}