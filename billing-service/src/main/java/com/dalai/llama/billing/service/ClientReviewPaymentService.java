package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.PreProductionServiceClient;
import com.dalai.llama.billing.client.TenantServiceClient;
import com.dalai.llama.billing.domain.entity.ClientReviewPayment;
import com.dalai.llama.billing.repository.ClientReviewPaymentRepository;
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

/**
 * The client pay-to-lock flow. Pricing: a configurable flat {@code platformBase} (what the
 * platform keeps -- COGS + opex + profit, competitor-validated) plus the creator's own
 * {@code Tenant.marginPercent} on top (their earnings, credited to their wallet on success).
 * Only the creator's margin touches a wallet; the platform's base is tracked as unsettled data.
 * Razorpay is reused via the same {@link PaymentGateway} the rest of billing already uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientReviewPaymentService {

    private final PaymentGateway paymentGateway;
    private final TenantServiceClient tenantServiceClient;
    private final WalletService walletService;
    private final ClientReviewPaymentRepository repository;
    private final PreProductionServiceClient preProductionServiceClient;

    @Value("${billing.client-review.platform-base-inr:5299}")
    private BigDecimal platformBase;

    @Value("${billing.client-review.default-creator-margin-percent:22.6}")
    private BigDecimal defaultCreatorMarginPercent;

    @Value("${billing.client-review.currency:INR}")
    private String currency;

    @Value("${razorpay.key-id:}")
    private String razorpayKeyId;

    /** Flat base price of one extra review round (beyond a project's included allowance). The
     * creator's margin is added on top, same as the lock flow -- all configurable. */
    @Value("${billing.client-review.extra-review-price-inr:500}")
    private BigDecimal extraReviewBase;

    /** Quote for one extra review round (₹500 base by default + the creator's margin). */
    public Quote extraReviewQuote(UUID tenantId, UUID projectId) {
        BigDecimal marginPercent = resolveCreatorMargin(tenantId);
        BigDecimal creatorAmount = extraReviewBase.multiply(marginPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = extraReviewBase.add(creatorAmount).setScale(2, RoundingMode.HALF_UP);
        return new Quote(extraReviewBase.setScale(2, RoundingMode.HALF_UP), creatorAmount, total, currency, marginPercent);
    }

    @Transactional
    public OrderResult createExtraReviewOrder(UUID tenantId, UUID projectId, String reviewToken) {
        Quote quote = extraReviewQuote(tenantId, projectId);
        String receipt = "rev_" + projectId.toString().replace("-", "").substring(0, 20);
        String gatewayOrderId = paymentGateway.createOrder(quote.totalAmount(), quote.currency(), receipt);
        Instant now = Instant.now();
        ClientReviewPayment payment = ClientReviewPayment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .reviewToken(reviewToken)
                .kind("EXTRA_REVIEW")
                .platformBase(quote.platformBase())
                .creatorAmount(quote.creatorAmount())
                .totalAmount(quote.totalAmount())
                .currency(quote.currency())
                .status("PENDING")
                .gatewayOrderId(gatewayOrderId)
                .settled(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.save(payment);
        return new OrderResult(payment.getId(), gatewayOrderId, quote.totalAmount(), quote.currency(), razorpayKeyId);
    }

    public Quote quote(UUID tenantId, UUID projectId) {
        BigDecimal marginPercent = resolveCreatorMargin(tenantId);
        BigDecimal creatorAmount = platformBase.multiply(marginPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = platformBase.add(creatorAmount).setScale(2, RoundingMode.HALF_UP);
        return new Quote(platformBase.setScale(2, RoundingMode.HALF_UP), creatorAmount, total, currency, marginPercent);
    }

    @Transactional
    public OrderResult createOrder(UUID tenantId, UUID projectId, String reviewToken) {
        Quote quote = quote(tenantId, projectId);
        String receipt = "clr_" + projectId.toString().replace("-", "").substring(0, 20);
        String gatewayOrderId = paymentGateway.createOrder(quote.totalAmount(), quote.currency(), receipt);

        Instant now = Instant.now();
        ClientReviewPayment payment = ClientReviewPayment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .reviewToken(reviewToken)
                .kind("LOCK")
                .platformBase(quote.platformBase())
                .creatorAmount(quote.creatorAmount())
                .totalAmount(quote.totalAmount())
                .currency(quote.currency())
                .status("PENDING")
                .gatewayOrderId(gatewayOrderId)
                .settled(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.save(payment);

        return new OrderResult(payment.getId(), gatewayOrderId, quote.totalAmount(), quote.currency(), razorpayKeyId);
    }

    /** Verifies the gateway signature, marks the payment SUCCESS, and credits the creator's margin
     * to their wallet (reference {@code CLIENT_REVIEW_PAYMENT:<projectId>} so the wallet ledger can
     * label it as client earnings). Idempotent on the order id -- a second verify of an already-
     * SUCCESS payment returns without double-crediting. The client-callback path: called from
     * PaymentController once the browser hands back Razorpay Checkout's response, so there's a
     * real per-payment signature to check here. */
    @Transactional
    public VerifyResult verify(String gatewayOrderId, String gatewayPaymentId, String signature) {
        ClientReviewPayment payment = repository.findByGatewayOrderId(gatewayOrderId)
                .orElseThrow(() -> new IllegalArgumentException("No client-review payment for order " + gatewayOrderId));

        if ("SUCCESS".equals(payment.getStatus())) {
            return new VerifyResult(payment.getId(), payment.getProjectId(), payment.getTenantId(), payment.getCreatorAmount(), true);
        }

        paymentGateway.verify(gatewayOrderId, gatewayPaymentId, signature);
        markCaptured(payment, gatewayPaymentId);
        return new VerifyResult(payment.getId(), payment.getProjectId(), payment.getTenantId(), payment.getCreatorAmount(), true);
    }

    /** The webhook path: Razorpay's own {@code payment.captured} callback confirms the charge
     * independently of whatever the client's browser managed to do, so this never depends on (and
     * never repeats) {@link #verify}'s per-payment signature check -- the webhook's own
     * authenticity is already established by the caller (see RazorpayWebhookOrchestrationService).
     * Shares {@link #markCaptured}'s idempotency guard, so it's harmless if {@link #verify} already
     * ran for this payment, or runs afterward. Unlike {@link #verify}, this also has no client
     * request in flight to drive pre-production-service forward once billing's own side is done --
     * that's the whole reason this method exists -- so it calls pre-production-service itself.
     * Best-effort: a failure here is logged, not thrown, since the payment is already durably
     * marked SUCCESS on billing's side regardless; see this method's caller for why the webhook
     * must still return 200 to Razorpay either way.
     *
     * @return true if {@code gatewayOrderId} was a client-review payment (found here, handled
     *         either way) -- false leaves the caller's own "genuinely unknown order" warning
     *         accurate instead of this method swallowing it silently. */
    @Transactional
    public boolean captureFromWebhook(String gatewayOrderId, String gatewayPaymentId) {
        ClientReviewPayment payment = repository.findByGatewayOrderId(gatewayOrderId).orElse(null);
        if (payment == null) {
            return false;
        }
        boolean alreadyCaptured = "SUCCESS".equals(payment.getStatus());
        if (!alreadyCaptured) {
            markCaptured(payment, gatewayPaymentId);
        }
        try {
            preProductionServiceClient.notifyClientReviewPaymentCaptured(payment.getTenantId(), payment.getProjectId(), payment.getKind());
        } catch (Exception ex) {
            log.warn("Could not notify pre-production-service of captured client-review payment {} (project {}): {}",
                    payment.getId(), payment.getProjectId(), ex.getMessage());
        }
        return true;
    }

    private void markCaptured(ClientReviewPayment payment, String gatewayPaymentId) {
        payment.setStatus("SUCCESS");
        payment.setGatewayPaymentId(gatewayPaymentId);
        payment.setUpdatedAt(Instant.now());
        repository.save(payment);

        if (payment.getCreatorAmount().signum() > 0) {
            walletService.credit(payment.getTenantId(), payment.getCreatorAmount(),
                    "CLIENT_REVIEW_PAYMENT:" + payment.getProjectId(), null, "clr-credit-" + payment.getId());
        }
    }

    private BigDecimal resolveCreatorMargin(UUID tenantId) {
        try {
            TenantServiceClient.TenantInfo tenant = tenantServiceClient.getTenant(tenantId);
            if (tenant != null && tenant.marginPercent() != null) {
                return tenant.marginPercent();
            }
        } catch (Exception ex) {
            log.warn("Could not resolve creator margin for tenant {}, using default: {}", tenantId, ex.getMessage());
        }
        return defaultCreatorMarginPercent;
    }

    public record Quote(BigDecimal platformBase, BigDecimal creatorAmount, BigDecimal totalAmount, String currency, BigDecimal creatorMarginPercent) {}

    public record OrderResult(UUID paymentId, String gatewayOrderId, BigDecimal amount, String currency, String keyId) {}

    public record VerifyResult(UUID paymentId, UUID projectId, UUID tenantId, BigDecimal creatorAmount, boolean success) {}
}
