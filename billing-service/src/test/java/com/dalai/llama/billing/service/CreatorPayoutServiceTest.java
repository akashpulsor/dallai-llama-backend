package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.CreativePlanningServiceClient;
import com.dalai.llama.billing.client.CreativePlanningServiceClient.ProjectQuote;
import com.dalai.llama.billing.domain.entity.ClientReviewPayment;
import com.dalai.llama.billing.domain.entity.CreatorPayout;
import com.dalai.llama.billing.domain.entity.CreatorPayoutAccount;
import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.enums.CreatorPayoutStatus;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.domain.entity.enums.PayoutMethod;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreatorPayoutServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID requirementId = UUID.randomUUID();

    private final CreatorPayoutRepository payouts = mock(CreatorPayoutRepository.class);
    private final CreatorPayoutAccountRepository accounts = mock(CreatorPayoutAccountRepository.class);
    private final ClientReviewPaymentRepository reviewPayments = mock(ClientReviewPaymentRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final UsageRecordRepository usage = mock(UsageRecordRepository.class);
    private final CreativePlanningServiceClient creativePlanning = mock(CreativePlanningServiceClient.class);
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final PayoutGateway payoutGateway = mock(PayoutGateway.class);
    private final WalletService walletService = mock(WalletService.class);
    private final CreatorPayoutService service = new CreatorPayoutService(payouts, accounts, reviewPayments,
            paymentRepository, usage, creativePlanning, paymentGateway, payoutGateway, walletService);

    private CreatorPayout payout;

    @BeforeEach
    void setUp() {
        payout = CreatorPayout.builder().id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId)
                .status(CreatorPayoutStatus.QUEUED).currency("INR").createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(payouts.findById(payout.getId())).thenReturn(Optional.of(payout));
        when(payouts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletService.getBalance(tenantId)).thenReturn(new BigDecimal("100000"));
    }

    @Test
    void paysWhatIsLeftOfTheClientsMoneyAfterProductionAndGatewayFees() {
        // City Professional: 1299 quoted, 324.75 paid on the brief by Razorpay, the 974.25 balance at lock.
        upfront("324.75", "RAZORPAY", "pay_up");
        lock("722.32", "251.93", "pay_lock");
        when(usage.sumCostByProjectId(tenantId, projectId)).thenReturn(new BigDecimal("540.00"));
        when(paymentGateway.fee("pay_up")).thenReturn(new BigDecimal("7.67"));
        when(paymentGateway.fee("pay_lock")).thenReturn(new BigDecimal("22.99"));
        account();

        service.prepare(payout.getId());

        // 1299 - 540 - (7.67 + 22.99)
        assertThat(payout.getClientPaid()).isEqualByComparingTo("1299.00");
        assertThat(payout.getProductionCharged()).isEqualByComparingTo("540.00");
        assertThat(payout.getGatewayFees()).isEqualByComparingTo("30.66");
        assertThat(payout.getAmount()).isEqualByComparingTo("728.34");
        assertThat(payout.getStatus()).isEqualTo(CreatorPayoutStatus.READY);
        verify(walletService).debit(eq(tenantId), eq(new BigDecimal("728.34")), eq(TransactionType.CREATOR_PAYOUT),
                eq("CREATOR_PAYOUT:" + projectId), isNull(), eq("creator-payout-" + payout.getId() + "-1"),
                anyString(), eq(projectId));
    }

    @Test
    void aBriefFundedFromTheCreatorsOwnWalletIsNotTheClientsMoney() {
        upfront("324.75", "WALLET", "WALLET_x");
        lock("722.32", "251.93", "pay_lock");
        when(usage.sumCostByProjectId(tenantId, projectId)).thenReturn(new BigDecimal("540.00"));
        when(paymentGateway.fee("pay_lock")).thenReturn(BigDecimal.ZERO);
        account();

        service.prepare(payout.getId());

        assertThat(payout.getClientPaid()).isEqualByComparingTo("974.25");
        assertThat(payout.getAmount()).isEqualByComparingTo("434.25");
        verify(paymentGateway, never()).fee("WALLET_x");
    }

    @Test
    void neverPaysOutMoreThanTheWalletStillHolds() {
        upfront("324.75", "RAZORPAY", "pay_up");
        lock("722.32", "251.93", "pay_lock");
        when(usage.sumCostByProjectId(tenantId, projectId)).thenReturn(new BigDecimal("540.00"));
        when(paymentGateway.fee(anyString())).thenReturn(BigDecimal.ZERO);
        when(walletService.getBalance(tenantId)).thenReturn(new BigDecimal("300.4567"));
        account();

        service.prepare(payout.getId());

        assertThat(payout.getAmount()).isEqualByComparingTo("300.45");
    }

    @Test
    void nothingIsSentWhenProductionAteTheWholeAmount() {
        lock("722.32", "251.93", "pay_lock");
        when(usage.sumCostByProjectId(tenantId, projectId)).thenReturn(new BigDecimal("1100.00"));
        when(paymentGateway.fee(anyString())).thenReturn(BigDecimal.ZERO);
        account();

        service.prepare(payout.getId());

        assertThat(payout.getStatus()).isEqualTo(CreatorPayoutStatus.NOTHING_TO_PAY);
        assertThat(payout.getAmount()).isEqualByComparingTo("0");
        verify(walletService, never()).debit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void waitsForAnAccountWithoutTouchingTheWallet_thenStopsRecomputingUntilOneIsAdded() {
        lock("722.32", "251.93", "pay_lock");
        when(usage.sumCostByProjectId(tenantId, projectId)).thenReturn(new BigDecimal("100.00"));
        when(paymentGateway.fee(anyString())).thenReturn(BigDecimal.ZERO);
        when(accounts.findById(tenantId)).thenReturn(Optional.empty());

        service.prepare(payout.getId());
        assertThat(payout.getStatus()).isEqualTo(CreatorPayoutStatus.AWAITING_ACCOUNT);
        assertThat(payout.getAmount()).isEqualByComparingTo("874.25");

        service.prepare(payout.getId());
        verify(usage).sumCostByProjectId(tenantId, projectId); // once: the second run saw no account and left it
        verify(walletService, never()).debit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aProcessedPayoutIsPaid() {
        ready("500.00");
        CreatorPayoutAccount account = account();
        when(payoutGateway.createPayout(account.getFundAccountId(), PayoutMethod.UPI, new BigDecimal("500.00"), payout.attemptReference()))
                .thenReturn(new PayoutResult("pout_1", "processed", "UTR123"));

        service.send(payout.getId());

        assertThat(payout.getStatus()).isEqualTo(CreatorPayoutStatus.PAID);
        assertThat(payout.getUtr()).isEqualTo("UTR123");
        assertThat(payout.getGatewayPayoutId()).isEqualTo("pout_1");
        assertThat(payout.getPaidAt()).isNotNull();
    }

    @Test
    void aRejectedPayoutGivesTheMoneyBackToTheWallet() {
        ready("500.00");
        account();
        when(payoutGateway.createPayout(anyString(), any(), any(), anyString()))
                .thenThrow(new PayoutRejectedException("invalid fund account"));

        service.send(payout.getId());

        assertThat(payout.getStatus()).isEqualTo(CreatorPayoutStatus.FAILED);
        verify(walletService).credit(eq(tenantId), eq(new BigDecimal("500.00")), eq(TransactionType.CREATOR_PAYOUT_REVERSAL),
                eq("CREATOR_PAYOUT_REVERSAL:" + projectId), isNull(), eq("creator-payout-reversal-" + payout.getId() + "-1"), anyString());
    }

    @Test
    void aTimeoutLeavesThePayoutReadyToBeSentAgainWithTheSameKey() {
        ready("500.00");
        account();
        when(payoutGateway.createPayout(anyString(), any(), any(), anyString())).thenThrow(new RuntimeException("timeout"));

        service.send(payout.getId());

        assertThat(payout.getStatus()).isEqualTo(CreatorPayoutStatus.READY);
        verify(walletService, never()).credit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aReversalAfterProcessingReturnsTheMoney_andALateProcessedEventCannotResurrectIt() throws Exception {
        ready("500.00");
        payout.setGatewayPayoutId("pout_1");
        payout.setStatus(CreatorPayoutStatus.PAID);
        when(payouts.findByGatewayPayoutId("pout_1")).thenReturn(Optional.of(payout));

        assertThat(service.onGatewayEvent(event("pout_1", "reversed"))).isTrue();
        assertThat(payout.getStatus()).isEqualTo(CreatorPayoutStatus.FAILED);

        service.onGatewayEvent(event("pout_1", "processed"));
        assertThat(payout.getStatus()).isEqualTo(CreatorPayoutStatus.FAILED);
        verify(walletService).credit(eq(tenantId), eq(new BigDecimal("500.00")), eq(TransactionType.CREATOR_PAYOUT_REVERSAL),
                any(), isNull(), any(), any());
    }

    @Test
    void aWebhookFindsAPayoutByItsReferenceWhenTheIdWasNeverRecorded() throws Exception {
        ready("500.00");
        payout.setStatus(CreatorPayoutStatus.READY);
        when(payouts.findByGatewayPayoutId("pout_9")).thenReturn(Optional.empty());

        service.onGatewayEvent(new ObjectMapper().readTree("""
                {"id":"pout_9","status":"processed","utr":"UTR9","reference_id":"%s"}""".formatted(payout.attemptReference())));

        assertThat(payout.getGatewayPayoutId()).isEqualTo("pout_9");
        assertThat(payout.getStatus()).isEqualTo(CreatorPayoutStatus.PAID);
    }

    @Test
    void anUnknownPayoutIsNotOurs() throws Exception {
        when(payouts.findByGatewayPayoutId("pout_x")).thenReturn(Optional.empty());

        assertThat(service.onGatewayEvent(event("pout_x", "processed"))).isFalse();
    }

    @Test
    void aRetriedPayoutIsANewDebitWithANewKey() {
        payout.setStatus(CreatorPayoutStatus.FAILED);
        payout.setAttempts(1);

        service.retry(payout.getId());
        assertThat(payout.getStatus()).isEqualTo(CreatorPayoutStatus.QUEUED);

        lock("722.32", "251.93", "pay_lock");
        when(usage.sumCostByProjectId(tenantId, projectId)).thenReturn(new BigDecimal("100.00"));
        when(paymentGateway.fee(anyString())).thenReturn(BigDecimal.ZERO);
        account();
        service.prepare(payout.getId());

        assertThat(payout.attemptReference()).endsWith("-2");
        verify(walletService).debit(eq(tenantId), any(), eq(TransactionType.CREATOR_PAYOUT), any(), isNull(),
                eq("creator-payout-" + payout.getId() + "-2"), anyString(), eq(projectId));
    }

    @Test
    void openingTwiceIsHarmless() {
        when(payouts.openIfAbsent(any(), eq(tenantId), eq(projectId))).thenReturn(1, 0);

        service.open(tenantId, projectId);
        service.open(tenantId, projectId);

        verifyNoInteractions(walletService, payoutGateway);
    }

    private void upfront(String amount, String gateway, String gatewayPaymentId) {
        when(creativePlanning.getProjectQuote(tenantId, projectId)).thenReturn(Optional.of(new ProjectQuote(
                10, new BigDecimal("1046.83"), new BigDecimal("1299.00"), new BigDecimal("24.09"), "INR",
                new BigDecimal(amount), true, requirementId)));
        when(paymentRepository.findByProjectRequirementIdOrderByCreatedAtDesc(requirementId)).thenReturn(List.of(
                Payment.builder().id(UUID.randomUUID()).tenantId(tenantId).amount(new BigDecimal(amount))
                        .status(PaymentStatus.SUCCESS).gateway(gateway).gatewayPaymentId(gatewayPaymentId).build()));
    }

    private void lock(String platformBase, String creatorAmount, String gatewayPaymentId) {
        BigDecimal base = new BigDecimal(platformBase);
        BigDecimal creator = new BigDecimal(creatorAmount);
        when(reviewPayments.findByTenantIdAndProjectIdAndStatus(tenantId, projectId, "SUCCESS")).thenReturn(List.of(
                ClientReviewPayment.builder().id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId).kind("LOCK")
                        .platformBase(base).creatorAmount(creator).totalAmount(base.add(creator)).currency("INR")
                        .status("SUCCESS").gatewayPaymentId(gatewayPaymentId).build()));
    }

    private CreatorPayoutAccount account() {
        CreatorPayoutAccount account = CreatorPayoutAccount.builder().tenantId(tenantId).method(PayoutMethod.UPI)
                .holderName("Asha Rao").vpa("asha@okhdfc").contactId("cont_1").fundAccountId("fa_1").build();
        when(accounts.findById(tenantId)).thenReturn(Optional.of(account));
        return account;
    }

    private void ready(String amount) {
        payout.setStatus(CreatorPayoutStatus.READY);
        payout.setAmount(new BigDecimal(amount));
        payout.setAttempts(1);
    }

    private static com.fasterxml.jackson.databind.JsonNode event(String id, String status) throws Exception {
        return new ObjectMapper().readTree("{\"id\":\"%s\",\"status\":\"%s\"}".formatted(id, status));
    }
}
