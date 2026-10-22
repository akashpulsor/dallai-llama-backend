package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.CreativePlanningServiceClient;
import com.dalai.llama.billing.client.CreativePlanningServiceClient.ProjectQuote;
import com.dalai.llama.billing.domain.entity.ClientReviewPayment;
import com.dalai.llama.billing.domain.entity.CreatorPayout;
import com.dalai.llama.billing.domain.entity.CreatorPayoutAccount;
import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.repository.ClientReviewPaymentRepository;
import com.dalai.llama.billing.repository.CreatorPayoutAccountRepository;
import com.dalai.llama.billing.repository.CreatorPayoutRepository;
import com.dalai.llama.billing.repository.PaymentRepository;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import com.dalai.llama.billing.service.payment.PaymentGateway;
import com.dalai.llama.billing.service.payment.PayoutGateway;
import com.dalai.llama.billing.service.payment.PayoutGateway.PayoutRejectedException;
import com.dalai.llama.billing.service.payment.PayoutGateway.PayoutResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus.AWAITING_ACCOUNT;
import static com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus.FAILED;
import static com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus.NOTHING_TO_PAY;
import static com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus.PAID;
import static com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus.QUEUED;
import static com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus.READY;
import static com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus.SENT;

/**
 * Pays a creator their share of a project once the client's final payment is in. Everything the
 * client paid lands in the creator's wallet first (the brief upfront, then the lock), and the
 * project's AI production is charged to that same wallet. What is left of the client's money
 * after production -- provider cost plus the platform's margin, as charged -- and after Razorpay's
 * fees on the client's payments, is the creator's, and goes to their bank or UPI -- through
 * RazorpayX when {@code razorpayx.payouts-enabled} is on, otherwise paid by hand by ops, who then
 * record the transfer's UTR ({@link #markPaid}).
 * <p>
 * The amount is worked out when the payout is prepared, then taken out of the wallet before it is
 * sent, so it can't also be spent on generation; a payout RazorpayX rejects or reverses gives it
 * back. It never exceeds the wallet balance. A brief funded from the creator's own wallet is the
 * creator's money, not the client's, so it isn't counted as paid.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorPayoutService {

    private static final String CAPTURED = "SUCCESS";
    private static final String WALLET_GATEWAY = "WALLET";
    /** RazorpayX's smallest payout. */
    private static final BigDecimal MINIMUM = BigDecimal.ONE;
    private static final Set<String> GATEWAY_FAILED = Set.of("reversed", "failed", "rejected", "cancelled");

    private final CreatorPayoutRepository payouts;
    private final CreatorPayoutAccountRepository accounts;
    private final ClientReviewPaymentRepository reviewPayments;
    private final PaymentRepository paymentRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final CreativePlanningServiceClient creativePlanningServiceClient;
    private final PaymentGateway paymentGateway;
    private final PayoutGateway payoutGateway;
    private final WalletService walletService;
    private final CreatorPayoutAccountService accountService;

    /** Off: ops pays each prepared payout by hand and marks it paid ({@link #markPaid}). */
    @Value("${razorpayx.payouts-enabled:false}")
    private boolean automatic;

    /** Opens the project's payout; a second call for the same project changes nothing. */
    @Transactional
    public void open(UUID tenantId, UUID projectId) {
        if (payouts.openIfAbsent(UUID.randomUUID(), tenantId, projectId) > 0) {
            log.info("Creator payout opened for project {} (tenant {})", projectId, tenantId);
        }
    }

    public List<UUID> toPrepare() {
        return idsIn(EnumSet.of(QUEUED, AWAITING_ACCOUNT));
    }

    public List<UUID> toSend() {
        return idsIn(EnumSet.of(READY));
    }

    /** Works out the amount and, when there is something to pay and somewhere to pay it, takes it
     * out of the wallet so {@link #send} can pay it. */
    @Transactional
    public void prepare(UUID payoutId) {
        CreatorPayout payout = payouts.findById(payoutId)
                .filter(p -> p.getStatus() == QUEUED || p.getStatus() == AWAITING_ACCOUNT)
                .orElse(null);
        if (payout == null) {
            return;
        }
        Optional<CreatorPayoutAccount> account = accounts.findById(payout.getTenantId());
        if (payout.getStatus() == AWAITING_ACCOUNT && account.isEmpty()) {
            return;
        }

        Settlement settlement = settle(payout.getTenantId(), payout.getProjectId());
        payout.setClientPaid(settlement.clientPaid());
        payout.setProductionCharged(settlement.productionCharged());
        payout.setGatewayFees(settlement.gatewayFees());

        BigDecimal amount = settlement.net().min(walletService.getBalance(payout.getTenantId()))
                .setScale(2, RoundingMode.DOWN);
        payout.setAmount(amount.max(BigDecimal.ZERO));
        if (amount.compareTo(MINIMUM) < 0) {
            payout.moveTo(NOTHING_TO_PAY, settlement.net().compareTo(MINIMUM) < 0 ? null
                    : "The wallet no longer holds this project's money");
            payouts.save(payout);
            return;
        }
        if (account.isEmpty()) {
            payout.moveTo(AWAITING_ACCOUNT, null);
            payouts.save(payout);
            return;
        }

        payout.setAttempts(payout.getAttempts() + 1);
        walletService.debit(payout.getTenantId(), amount, TransactionType.CREATOR_PAYOUT,
                "CREATOR_PAYOUT:" + payout.getProjectId(), null, "creator-payout-" + payout.attemptReference(),
                "Paid out to your bank / UPI", payout.getProjectId());
        payout.moveTo(READY, null);
        payouts.save(payout);
    }

    /** Hands a prepared payout to RazorpayX. Safe to repeat: the attempt reference is the
     * idempotency key, so a retry after a lost response gets back the payout already made. */
    @Transactional
    public void send(UUID payoutId) {
        CreatorPayout payout = payouts.findById(payoutId).filter(p -> p.getStatus() == READY).orElse(null);
        if (payout == null) {
            return;
        }
        CreatorPayoutAccount account = accounts.findById(payout.getTenantId()).orElse(null);
        if (account == null) {
            fail(payout, "No payout account");
            payouts.save(payout);
            return;
        }
        try {
            String fundAccountId = accountService.fundAccountId(account);
            PayoutResult result = payoutGateway.createPayout(
                    fundAccountId, account.getMethod(), payout.getAmount(), payout.attemptReference());
            payout.setFundAccountId(fundAccountId);
            payout.setGatewayPayoutId(result.id());
            apply(payout, result.status(), result.utr(), null);
        } catch (PayoutRejectedException ex) {
            fail(payout, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Creator payout {} not sent yet, will retry: {}", payout.getId(), ex.getMessage());
            payout.moveTo(READY, "Retrying: " + ex.getMessage());
        }
        payouts.save(payout);
    }

    /** A RazorpayX {@code payout.*} webhook. Matched by payout id, or by our attempt reference
     * when the response that carried the id was lost.
     *
     * @return false when the payout isn't one of ours */
    @Transactional
    public boolean onGatewayEvent(JsonNode entity) {
        String gatewayPayoutId = entity.path("id").asText();
        CreatorPayout payout = payouts.findByGatewayPayoutId(gatewayPayoutId)
                .or(() -> byReference(entity.path("reference_id").asText()))
                .orElse(null);
        if (payout == null) {
            return false;
        }
        if (payout.getGatewayPayoutId() == null) {
            payout.setGatewayPayoutId(gatewayPayoutId);
        }
        String reason = Stream.of(entity.path("status_details").path("description"), entity.path("failure_reason"))
                .map(JsonNode::asText).filter(text -> !text.isBlank() && !"null".equals(text))
                .findFirst().orElse(null);
        apply(payout, entity.path("status").asText(), textOrNull(entity.path("utr")), reason);
        payouts.save(payout);
        return true;
    }

    /** Ops paid a prepared payout by hand -- a bank or UPI transfer from outside the platform --
     * and records its reference. The amount already left the wallet when it was prepared. Refused
     * while automatic payouts are on, when the same payout may already be on its way via RazorpayX. */
    @Transactional
    public CreatorPayout markPaid(UUID payoutId, String utr) {
        if (automatic) {
            throw new IllegalArgumentException("Automatic payouts are on -- RazorpayX is sending this one");
        }
        String reference = utr == null ? "" : utr.trim();
        if (reference.isEmpty() || reference.length() > 64) {
            throw new IllegalArgumentException("Enter the UTR / UPI reference of the transfer");
        }
        CreatorPayout payout = payouts.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("No payout " + payoutId));
        if (payout.getStatus() != READY) {
            throw new IllegalArgumentException("Only a payout ready to send can be marked paid; this one is " + payout.getStatus());
        }
        payout.setUtr(reference);
        payout.setPaidAt(Instant.now());
        payout.moveTo(PAID, null);
        return payouts.save(payout);
    }

    /** Ops: send a failed payout again. The amount is worked out afresh. */
    @Transactional
    public CreatorPayout retry(UUID payoutId) {
        CreatorPayout payout = payouts.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("No payout " + payoutId));
        if (payout.getStatus() != FAILED) {
            throw new IllegalArgumentException("Only a failed payout can be retried; this one is " + payout.getStatus());
        }
        payout.moveTo(QUEUED, null);
        return payouts.save(payout);
    }

    @Transactional(readOnly = true)
    public List<CreatorPayout> forTenant(UUID tenantId) {
        return payouts.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** The last 200 payouts with where each one is to be paid -- what ops needs to pay by hand. */
    @Transactional(readOnly = true)
    public List<OpsPayout> recentForOps() {
        List<CreatorPayout> recent = payouts.findTop200ByOrderByCreatedAtDesc();
        Map<UUID, CreatorPayoutAccount> payees = accounts.findAllById(
                        recent.stream().map(CreatorPayout::getTenantId).distinct().toList()).stream()
                .collect(Collectors.toMap(CreatorPayoutAccount::getTenantId, Function.identity()));
        return recent.stream().map(p -> new OpsPayout(p, payees.get(p.getTenantId()))).toList();
    }

    public record OpsPayout(CreatorPayout payout, CreatorPayoutAccount payee) {}

    /** Moves a payout by RazorpayX's status. A payout already settled one way is never moved back,
     * except a reversal after it was processed, which really does return the money. */
    private void apply(CreatorPayout payout, String gatewayStatus, String utr, String reason) {
        if (payout.getStatus() == FAILED) {
            return;
        }
        if (GATEWAY_FAILED.contains(gatewayStatus)) {
            fail(payout, reason != null ? reason : "RazorpayX " + gatewayStatus + " the payout");
            return;
        }
        if (payout.getStatus() == PAID) {
            return;
        }
        if ("processed".equals(gatewayStatus)) {
            payout.setUtr(utr);
            payout.setPaidAt(Instant.now());
            payout.moveTo(PAID, null);
        } else {
            payout.moveTo(SENT, null);
        }
    }

    private void fail(CreatorPayout payout, String reason) {
        walletService.credit(payout.getTenantId(), payout.getAmount(), TransactionType.CREATOR_PAYOUT_REVERSAL,
                "CREATOR_PAYOUT_REVERSAL:" + payout.getProjectId(), null,
                "creator-payout-reversal-" + payout.attemptReference(), "Payout returned to your wallet: " + reason);
        payout.moveTo(FAILED, reason);
    }

    private Settlement settle(UUID tenantId, UUID projectId) {
        List<ClientReviewPayment> review = reviewPayments.findByTenantIdAndProjectIdAndStatus(tenantId, projectId, CAPTURED);
        Optional<Payment> upfront = creativePlanningServiceClient.getProjectQuote(tenantId, projectId)
                .map(ProjectQuote::requirementId)
                .flatMap(requirementId -> paymentRepository.findByProjectRequirementIdOrderByCreatedAtDesc(requirementId).stream()
                        .filter(p -> p.getStatus() == PaymentStatus.SUCCESS && tenantId.equals(p.getTenantId()))
                        .findFirst())
                .filter(p -> !WALLET_GATEWAY.equals(p.getGateway()));

        BigDecimal clientPaid = upfront.map(Payment::getAmount).orElse(BigDecimal.ZERO)
                .add(review.stream().map(ClientReviewPayment::walletCredit).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal fees = Stream.concat(upfront.map(Payment::getGatewayPaymentId).stream(),
                        review.stream().map(ClientReviewPayment::getGatewayPaymentId))
                .filter(Objects::nonNull)
                .map(paymentGateway::fee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Settlement(clientPaid.setScale(2, RoundingMode.HALF_UP),
                usageRecordRepository.sumCostByProjectId(tenantId, projectId).setScale(2, RoundingMode.HALF_UP),
                fees.setScale(2, RoundingMode.HALF_UP));
    }

    private Optional<CreatorPayout> byReference(String reference) {
        try {
            return payouts.findById(UUID.fromString(reference.substring(0, 36)));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private List<UUID> idsIn(Set<CreatorPayoutStatus> statuses) {
        return payouts.findByStatusIn(statuses).stream().map(CreatorPayout::getId).toList();
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    record Settlement(BigDecimal clientPaid, BigDecimal productionCharged, BigDecimal gatewayFees) {
        BigDecimal net() {
            return clientPaid.subtract(productionCharged).subtract(gatewayFees);
        }
    }
}
