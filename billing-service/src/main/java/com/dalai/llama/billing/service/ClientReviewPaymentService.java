package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.CreativePlanningServiceClient;
import com.dalai.llama.billing.client.CreativePlanningServiceClient.ProjectQuote;
import com.dalai.llama.billing.client.PreProductionServiceClient;
import com.dalai.llama.billing.client.TenantServiceClient;
import com.dalai.llama.billing.domain.entity.ClientReviewPayment;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
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
 * The client pay-to-lock flow. Pricing: a project sold through a quoted brief locks for the
 * <b>balance</b> of that quote -- {@code quotedTotalPrice} minus whatever the client already paid
 * upfront on the brief -- so the review page charges exactly what the client was quoted, never
 * twice. A project with no quote (chat-originated) falls back to a configurable flat {@code
 * platformBase} plus the creator's own {@code Tenant.marginPercent} on top. The client pays the
 * platform; a captured lock then credits the creator's wallet with all of it -- the creator's
 * share as earnings, the production share as a top-up for the AI usage that wallet was charged
 * (see {@link ClientReviewPayment#walletCredit}). Razorpay is reused via the same {@link
 * PaymentGateway} the rest of billing already uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientReviewPaymentService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PaymentGateway paymentGateway;
    private final TenantServiceClient tenantServiceClient;
    private final WalletService walletService;
    private final ClientReviewPaymentRepository repository;
    private final PreProductionServiceClient preProductionServiceClient;
    private final CreativePlanningServiceClient creativePlanningServiceClient;
    private final VideoPricingService videoPricingService;

    @Value("${billing.client-review.platform-base-inr:5299}")
    private BigDecimal platformBase;

    @Value("${billing.client-review.default-creator-margin-percent:22.6}")
    private BigDecimal defaultCreatorMarginPercent;

    @Value("${billing.client-review.currency:INR}")
    private String currency;

    @Value("${razorpay.key-id:}")
    private String razorpayKeyId;

    /** Flat base price of one extra review round (beyond a project's included allowance). The
     * creator's margin is added on top, same as the unquoted lock flow -- all configurable. */
    @Value("${billing.client-review.extra-review-price-inr:100}")
    private BigDecimal extraReviewBase;

    /** Quote for one extra review round (₹100 base by default + the creator's margin). */
    public Quote extraReviewQuote(UUID tenantId, UUID projectId) {
        return marginOnTop(tenantId, extraReviewBase);
    }

    @Transactional
    public OrderResult createExtraReviewOrder(UUID tenantId, UUID projectId, String reviewToken) {
        return openOrder(tenantId, projectId, reviewToken, "EXTRA_REVIEW", "rev_", extraReviewQuote(tenantId, projectId));
    }

    /** What the client pays to lock: the quoted balance, or the flat price when unquoted. A zero
     * {@code totalAmount} means the brief was paid in full -- pre-production-service locks without
     * an order in that case. */
    public Quote quote(UUID tenantId, UUID projectId) {
        return creativePlanningServiceClient.getProjectQuote(tenantId, projectId)
                .map(project -> balanceOf(tenantId, project))
                .orElseGet(() -> marginOnTop(tenantId, platformBase));
    }

    @Transactional
    public OrderResult createOrder(UUID tenantId, UUID projectId, String reviewToken) {
        Quote quote = quote(tenantId, projectId);
        if (quote.totalAmount().signum() <= 0) {
            throw new IllegalArgumentException("Nothing left to pay for project " + projectId + " -- lock it without a payment");
        }
        return openOrder(tenantId, projectId, reviewToken, "LOCK", "clr_", quote);
    }

    /** The quoted total already has the creator's margin baked in ({@code platformCost x (1 +
     * m/100)}), so the creator's share of the balance is split back out of it -- adding the margin
     * again would charge the client more than they were quoted. */
    private Quote balanceOf(UUID tenantId, ProjectQuote project) {
        BigDecimal paidUpfront = project.paidUpfront();
        BigDecimal balance = project.quotedTotalPrice().subtract(paidUpfront)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal marginPercent = project.quotedCreatorMarginPercent() != null
                ? project.quotedCreatorMarginPercent()
                : resolveCreatorMargin(tenantId);
        BigDecimal creatorAmount = balance.multiply(marginPercent)
                .divide(HUNDRED.add(marginPercent), 2, RoundingMode.HALF_UP);
        String quoteCurrency = project.quotedCurrency() != null ? project.quotedCurrency() : currency;
        return new Quote(balance.subtract(creatorAmount), creatorAmount, balance, quoteCurrency, marginPercent,
                project.quotedTotalPrice(), paidUpfront, videoPricingService.productionCharges(project));
    }

    private Quote marginOnTop(UUID tenantId, BigDecimal base) {
        BigDecimal marginPercent = resolveCreatorMargin(tenantId);
        BigDecimal creatorAmount = base.multiply(marginPercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal total = base.add(creatorAmount).setScale(2, RoundingMode.HALF_UP);
        return new Quote(base.setScale(2, RoundingMode.HALF_UP), creatorAmount, total, currency, marginPercent, null, null, null);
    }

    private OrderResult openOrder(UUID tenantId, UUID projectId, String reviewToken, String kind, String receiptPrefix, Quote quote) {
        String receipt = receiptPrefix + projectId.toString().replace("-", "").substring(0, 20);
        String gatewayOrderId = paymentGateway.createOrder(quote.totalAmount(), quote.currency(), receipt);
        Instant now = Instant.now();
        ClientReviewPayment payment = ClientReviewPayment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .projectId(projectId)
                .reviewToken(reviewToken)
                .kind(kind)
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
        if (payment.productionFunding().signum() > 0) {
            walletService.credit(payment.getTenantId(), payment.productionFunding(), TransactionType.RECHARGE,
                    "CLIENT_PRODUCTION_FUNDING:" + payment.getProjectId(), null, "clr-production-" + payment.getId(),
                    "Client payment for this project's production");
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

    /** {@code quotedTotalPrice}/{@code paidUpfront}/{@code production} are null for an unquoted
     * (flat-priced) project; {@code production} is the line-by-line split the client is shown. */
    public record Quote(BigDecimal platformBase, BigDecimal creatorAmount, BigDecimal totalAmount, String currency,
                        BigDecimal creatorMarginPercent, BigDecimal quotedTotalPrice, BigDecimal paidUpfront,
                        ProductionCharges production) {}

    public record OrderResult(UUID paymentId, String gatewayOrderId, BigDecimal amount, String currency, String keyId) {}

    public record VerifyResult(UUID paymentId, UUID projectId, UUID tenantId, BigDecimal creatorAmount, boolean success) {}
}
