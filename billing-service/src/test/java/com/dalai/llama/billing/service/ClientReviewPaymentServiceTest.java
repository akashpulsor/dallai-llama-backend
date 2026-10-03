package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.CreativePlanningServiceClient;
import com.dalai.llama.billing.client.CreativePlanningServiceClient.ProjectQuote;
import com.dalai.llama.billing.client.PreProductionServiceClient;
import com.dalai.llama.billing.client.TenantServiceClient;
import com.dalai.llama.billing.domain.entity.ClientReviewPayment;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.repository.ClientReviewPaymentRepository;
import com.dalai.llama.billing.service.payment.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientReviewPaymentServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final TenantServiceClient tenantServiceClient = mock(TenantServiceClient.class);
    private final CreativePlanningServiceClient creativePlanning = mock(CreativePlanningServiceClient.class);
    private final WalletService walletService = mock(WalletService.class);
    private final ClientReviewPaymentRepository payments = mock(ClientReviewPaymentRepository.class);
    private ClientReviewPaymentService service;

    @BeforeEach
    void setUp() {
        service = new ClientReviewPaymentService(paymentGateway, tenantServiceClient, walletService,
                payments, mock(PreProductionServiceClient.class), creativePlanning,
                VideoPricingFixture.withDefaults());
        ReflectionTestUtils.setField(service, "platformBase", new BigDecimal("5299"));
        ReflectionTestUtils.setField(service, "defaultCreatorMarginPercent", new BigDecimal("22.6"));
        ReflectionTestUtils.setField(service, "currency", "INR");
        ReflectionTestUtils.setField(service, "extraReviewBase", new BigDecimal("100"));
        when(tenantServiceClient.getTenant(any())).thenReturn(null);
    }

    @Test
    void quotedProjectLocksForTheBalanceLeftAfterTheUpfrontPayment() {
        // 25% of a 3450 quote was paid on the brief; margin 15% is already inside the 3450.
        quote(new ProjectQuote(30, new BigDecimal("3000.00"), new BigDecimal("3450.00"), new BigDecimal("15"), "INR", new BigDecimal("862.50"), true));

        ClientReviewPaymentService.Quote quote = service.quote(tenantId, projectId);

        assertThat(quote.quotedTotalPrice()).isEqualByComparingTo("3450.00");
        assertThat(quote.paidUpfront()).isEqualByComparingTo("862.50");
        assertThat(quote.totalAmount()).isEqualByComparingTo("2587.50");
        assertThat(quote.creatorAmount()).isEqualByComparingTo("337.50");
        assertThat(quote.platformBase().add(quote.creatorAmount())).isEqualByComparingTo(quote.totalAmount());
        assertThat(quote.production().videoProduction()).isEqualByComparingTo("3000.00");
        assertThat(quote.production().musicProduction()).isEqualByComparingTo("450.00");
    }

    @Test
    void aQuoteOverriddenBelowCostNeverSplitsIntoMoreThanTheClientPays() {
        // The Verified Difference: quoted 1300 against a 7586 platform cost (margin -82.85), 1% upfront.
        quote(new ProjectQuote(60, new BigDecimal("1300.00"), new BigDecimal("1300.00"), new BigDecimal("-82.85"), "INR",
                new BigDecimal("13.00"), true));

        ClientReviewPaymentService.Quote quote = service.quote(tenantId, projectId);

        assertThat(quote.totalAmount()).isEqualByComparingTo("1287.00");
        assertThat(quote.creatorAmount()).isEqualByComparingTo("0.00");
        assertThat(quote.platformBase()).isEqualByComparingTo("1287.00");
    }

    @Test
    void unfundedBriefChargesTheFullQuoteAtLock() {
        quote(new ProjectQuote(30, new BigDecimal("3000.00"), new BigDecimal("3450.00"), new BigDecimal("15"), "INR", new BigDecimal("862.50"), false));

        assertThat(service.quote(tenantId, projectId).totalAmount()).isEqualByComparingTo("3450.00");
    }

    @Test
    void briefPaidInFullLeavesNothingToPayAndRefusesAnOrder() {
        quote(new ProjectQuote(30, new BigDecimal("3000.00"), new BigDecimal("3450.00"), new BigDecimal("15"), "INR", new BigDecimal("3450.00"), true));

        assertThat(service.quote(tenantId, projectId).totalAmount()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> service.createOrder(tenantId, projectId, "token"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void unquotedProjectKeepsTheFlatBasePlusCreatorMargin() {
        when(creativePlanning.getProjectQuote(tenantId, projectId)).thenReturn(Optional.empty());

        ClientReviewPaymentService.Quote quote = service.quote(tenantId, projectId);

        assertThat(quote.totalAmount()).isEqualByComparingTo("6496.57");
        assertThat(quote.quotedTotalPrice()).isNull();
        assertThat(quote.paidUpfront()).isNull();
        assertThat(quote.production()).isNull();
    }

    @Test
    void aCapturedLockPutsTheWholePaymentInTheCreatorsWallet() {
        ClientReviewPayment lock = ClientReviewPayment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId).kind("LOCK").status("PENDING")
                .platformBase(new BigDecimal("1046.82")).creatorAmount(new BigDecimal("252.18"))
                .totalAmount(new BigDecimal("1299.00")).currency("INR").gatewayOrderId("order_1").build();
        when(payments.findByGatewayOrderId("order_1")).thenReturn(Optional.of(lock));

        service.verify("order_1", "pay_1", "sig");

        verify(walletService).credit(tenantId, new BigDecimal("252.18"),
                "CLIENT_REVIEW_PAYMENT:" + projectId, null, "clr-credit-" + lock.getId());
        verify(walletService).credit(eq(tenantId), eq(new BigDecimal("1046.82")), eq(TransactionType.RECHARGE),
                eq("CLIENT_PRODUCTION_FUNDING:" + projectId), isNull(), eq("clr-production-" + lock.getId()), anyString());
    }

    @Test
    void anExtraReviewRoundIsOneHundredPlusTheCreatorsMargin() {
        ClientReviewPaymentService.Quote quote = service.extraReviewQuote(tenantId, projectId);

        assertThat(quote.platformBase()).isEqualByComparingTo("100.00");
        assertThat(quote.creatorAmount()).isEqualByComparingTo("22.60");
        assertThat(quote.totalAmount()).isEqualByComparingTo("122.60");
    }

    private void quote(ProjectQuote projectQuote) {
        when(creativePlanning.getProjectQuote(tenantId, projectId)).thenReturn(Optional.of(projectQuote));
    }
}
