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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    @Value("${razorpay.key-id:}")
    private String razorpayKeyId;

    // =========================
    // CREATE PAYMENT
    // =========================
    @Override
    @Transactional
    public UUID createPayment(UUID tenantId, BigDecimal amount, String description) {
        return createPaymentOrder(tenantId, null, amount, description, null).paymentId();
    }

    @Override
    @Transactional
    public UUID createPayment(UUID tenantId, String currency, BigDecimal amount,
                              String description, UUID subscriptionId) {
        return createPaymentOrder(tenantId, currency, amount, description, subscriptionId).paymentId();
    }

    @Override
    @Transactional
    public PaymentOrderResult createPaymentOrder(UUID tenantId, String currency, BigDecimal amount,
                                                 String description, UUID subscriptionId) {
        BigDecimal rechargeAmount = validateRechargeAmount(amount);

        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));
        String paymentCurrency = normalizeCurrency(currency, wallet.getCurrency());

        String gatewayOrderId;
        try {
            gatewayOrderId = paymentGateway.createOrder(
                    rechargeAmount, paymentCurrency, razorpayReceipt(tenantId));
        } catch (Exception e) {
            log.warn("Failed to create Razorpay payment order tenantId={} amount={} currency={} cause={}",
                    tenantId, rechargeAmount, paymentCurrency, e.getMessage(), e);
            throw new PaymentFailedException("Failed to create payment order", e);
        }

        Payment payment = Payment.create(
                tenantId, wallet.getId(), rechargeAmount,
                paymentCurrency, "RAZORPAY",
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

        return new PaymentOrderResult(
                payment.getId(),
                payment.getGatewayOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                razorpayKeyId,
                payment.getStatus().name()
        );
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

        handlePaymentSuccess(payment.getTenantId(), payment.getId(), gatewayOrderId, paymentId, signature);
    }

    @Override
    @Transactional
    public void handlePaymentSuccess(UUID tenantId, UUID paymentId,
                                     String gatewayOrderId, String gatewayPaymentId, String signature) {
        if (tenantId == null || paymentId == null) {
            throw new IllegalArgumentException("Tenant and payment id are required");
        }
        if (isBlank(gatewayOrderId) || isBlank(gatewayPaymentId) || isBlank(signature)) {
            throw new IllegalArgumentException("Missing Razorpay payment verification fields");
        }

        Payment payment = paymentRepository.findById(paymentId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new PaymentFailedException("Payment not found: " + paymentId));

        if (!gatewayOrderId.equals(payment.getGatewayOrderId())) {
            throw new PaymentFailedException("Payment order mismatch");
        }

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            if (gatewayPaymentId.equals(payment.getGatewayPaymentId())) {
                log.info("Payment {} already verified; skipping duplicate callback", paymentId);
                return;
            }
            throw new PaymentFailedException("Payment already verified with a different gateway payment id");
        }

        paymentGateway.verify(gatewayOrderId, gatewayPaymentId, signature);

        PaymentStatus previous = payment.markSuccess(gatewayPaymentId, signature);
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
                "PAYMENT:" + gatewayPaymentId,
                payment.getSubscriptionId(),
                "PAYMENT:" + payment.getId()
        );

        eventProducer.publishPaymentReceived(payment.toEvent());
    }

    private BigDecimal validateRechargeAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        BigDecimal normalized = amount.setScale(2, RoundingMode.HALF_UP);
        if (normalized.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("Minimum recharge amount is INR 1.00 (100 paise)");
        }
        return normalized;
    }

    private String normalizeCurrency(String requestedCurrency, String walletCurrency) {
        String walletLedgerCurrency = normalizeCurrencyCode(walletCurrency);
        String requested = normalizeCurrencyCode(requestedCurrency);
        if (!isBlank(walletLedgerCurrency)) {
            if (!isBlank(requested) && !walletLedgerCurrency.equals(requested)) {
                log.info("Using wallet ledger currency {} for recharge instead of requested currency {}",
                        walletLedgerCurrency, requested);
            }
            return walletLedgerCurrency;
        }
        if (isBlank(requested)) {
            throw new IllegalArgumentException("Invalid currency: " + requestedCurrency);
        }
        return requested;
    }

    private String normalizeCurrencyCode(String currency) {
        if (isBlank(currency)) {
            return "";
        }
        String normalized = currency.trim().toUpperCase();
        if (normalized.length() != 3) {
            throw new IllegalArgumentException("Invalid currency: " + currency);
        }
        return normalized;
    }

    private String razorpayReceipt(UUID tenantId) {
        String compactTenantId = tenantId == null
                ? "tenant"
                : tenantId.toString().replace("-", "");
        String suffix = Long.toString(System.currentTimeMillis(), 36);
        String prefix = compactTenantId.length() > 22 ? compactTenantId.substring(0, 22) : compactTenantId;
        return ("rcpt_" + prefix + "_" + suffix).substring(0, Math.min(40, 6 + prefix.length() + suffix.length()));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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
    // REFUND (subscription wallet payment, NOT Razorpay cash refund)
    // =========================

    /**
     * Refund a subscription wallet payment back to the tenant's wallet.
     *
     * Triggered when subscription activation fails after the wallet was debited.
     * Credits the wallet via the standard service path so transaction recording,
     * event publishing, and STOMP broadcast all happen exactly as for any other
     * wallet credit.
     *
     * Idempotency — three layers:
     *   1. Tenant validation guards against malicious/buggy events refunding
     *      the wrong wallet.
     *   2. Payment status check (REFUNDED → no-op) blocks duplicate runs
     *      after the first successful refund.
     *   3. walletService.credit() idempotency key keyed off paymentId blocks
     *      double-credit even if status check is somehow bypassed by a race.
     *
     * NOT a Razorpay cash refund — that's RefundServiceImpl, which pushes
     * money to the user's bank account. This is purely a platform-internal
     * wallet adjustment for subscription failure rollback.
     *
     * Future: a (paymentId, reason) overload may be added if a caller has
     * only the paymentId and not the tenant/subscription context.
     */
    @Override
    @Transactional
    public void refundSubscriptionPayment(UUID tenantId, UUID paymentId,
                                          UUID subscriptionId, String reason) {
        if (paymentId == null) {
            log.warn("refundSubscriptionPayment called with null paymentId — skipping");
            return;
        }

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));

        // Sanity: tenant on the event must match payment's tenant. Mismatch indicates
        // a bug or tampered event — fail loudly rather than refund the wrong wallet.
        if (!payment.getTenantId().equals(tenantId)) {
            throw new IllegalStateException(
                    "Tenant mismatch: event tenantId=" + tenantId
                            + ", payment.tenantId=" + payment.getTenantId()
                            + ", paymentId=" + paymentId);
        }

        // Idempotency: already refunded → no-op
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment {} already REFUNDED — skipping (idempotent)", paymentId);
            return;
        }

        // Only refund SUCCESS payments. Anything else (PENDING/FAILED/CANCELLED) is
        // either not yet captured or never was, so there's nothing to refund.
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            log.warn("Cannot refund payment {} in status {} — skipping",
                    paymentId, payment.getStatus());
            return;
        }

        // Credit wallet via the standard service path. Idempotency key keyed off
        // paymentId ensures Kafka redelivery cannot double-credit even if the
        // payment status check above somehow lets two concurrent attempts through.
        String idempotencyKey = "REFUND:SUBSCRIPTION:" + paymentId;
        walletService.credit(
                tenantId,
                payment.getAmount(),
                "REFUND:SUBSCRIPTION_FAILED:" + paymentId,
                subscriptionId,
                idempotencyKey
        );

        PaymentStatus previous = payment.markRefunded(reason);
        paymentRepository.save(payment);

        paymentEventRepository.save(PaymentEvent.record(
                payment,
                previous,
                PaymentStatus.REFUNDED,
                "REFUND:" + reason,
                "SYSTEM"
        ));

        log.info("Subscription payment refunded: tenantId={} paymentId={} subscriptionId={} amount={}",
                tenantId, paymentId, subscriptionId, payment.getAmount());
    }
}
