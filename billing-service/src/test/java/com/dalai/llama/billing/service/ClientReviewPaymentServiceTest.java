package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.CreativePlanningServiceClient;
import com.dalai.llama.billing.client.CreativePlanningServiceClient.ProjectQuote;
import com.dalai.llama.billing.client.PreProductionServiceClient;
import com.dalai.llama.billing.client.TenantServiceClient;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientReviewPaymentServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final TenantServiceClient tenantServiceClient = mock(TenantServiceClient.class);
    private final CreativePlanningServiceClient creativePlanning = mock(CreativePlanningServiceClient.class);
    private ClientReviewPaymentService service;

    @BeforeEach
    void setUp() {
        service = new ClientReviewPaymentService(paymentGateway, tenantServiceClient, mock(WalletService.class),
                mock(ClientReviewPaymentRepository.class), mock(PreProductionServiceClient.class), creativePlanning,
                VideoPricingFixture.withDefaults());
        ReflectionTestUtils.setField(service, "platformBase", new BigDecimal("5299"));
        ReflectionTestUtils.setField(service, "defaultCreatorMarginPercent", new BigDecimal("22.6"));
        ReflectionTestUtils.setField(service, "currency", "INR");
        ReflectionTestUtils.setField(service, "extraReviewBase", new BigDecimal("500"));
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

    private void quote(ProjectQuote projectQuote) {
        when(creativePlanning.getProjectQuote(tenantId, projectId)).thenReturn(Optional.of(projectQuote));
    }
}
