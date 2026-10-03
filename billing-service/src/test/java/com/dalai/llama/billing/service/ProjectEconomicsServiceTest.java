package com.dalai.llama.billing.service;

import com.dalai.llama.billing.client.CreativePlanningServiceClient;
import com.dalai.llama.billing.client.CreativePlanningServiceClient.ProjectQuote;
import com.dalai.llama.billing.domain.entity.ClientReviewPayment;
import com.dalai.llama.billing.repository.ClientReviewPaymentRepository;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectEconomicsServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final CreativePlanningServiceClient creativePlanning = mock(CreativePlanningServiceClient.class);
    private final UsageRecordRepository usage = mock(UsageRecordRepository.class);
    private final ClientReviewPaymentRepository reviewPayments = mock(ClientReviewPaymentRepository.class);
    private final ProjectEconomicsService service = new ProjectEconomicsService(
            creativePlanning, VideoPricingFixture.withDefaults(), usage, reviewPayments);

    @Test
    void splitsAQuotedPaidProjectIntoCustomerCreatorAndPlatform() {
        // City Professional: 1299 quoted, 25% (324.75) paid on the brief, balance paid at lock.
        when(creativePlanning.getProjectQuote(tenantId, projectId)).thenReturn(Optional.of(new ProjectQuote(
                10, new BigDecimal("1046.83"), new BigDecimal("1299.00"), new BigDecimal("24.09"), "INR",
                new BigDecimal("324.75"), true)));
        when(reviewPayments.findByTenantIdAndProjectIdAndStatus(tenantId, projectId, "SUCCESS"))
                .thenReturn(List.of(payment("LOCK", "785.14", "189.11")));
        when(usage.sumCostByProjectId(tenantId, projectId)).thenReturn(new BigDecimal("120.00"));
        when(usage.sumRawCostByProjectId(tenantId, projectId)).thenReturn(new BigDecimal("100.00"));

        ProjectEconomics economics = service.forProject(tenantId, projectId);

        assertThat(economics.production().musicProduction()).isEqualByComparingTo("252.17");
        assertThat(economics.customer().totalPaid()).isEqualByComparingTo("1299.00");
        // The whole lock reaches the wallet, so the creator keeps the quote minus actual usage,
        // and the platform earns only its margin on that usage.
        assertThat(economics.creator().received()).isEqualByComparingTo("1299.00");
        assertThat(economics.creator().profit()).isEqualByComparingTo("1179.00");
        assertThat(economics.platform().usageMargin()).isEqualByComparingTo("20.00");
        assertThat(economics.platform().reviewPaymentShare()).isEqualByComparingTo("0.00");
        assertThat(economics.platform().profit()).isEqualByComparingTo("20.00");
    }

    @Test
    void theCreatorNeverSeesPlatformFigures() {
        when(creativePlanning.getProjectQuote(tenantId, projectId)).thenReturn(Optional.empty());
        when(reviewPayments.findByTenantIdAndProjectIdAndStatus(tenantId, projectId, "SUCCESS")).thenReturn(List.of());
        when(usage.sumCostByProjectId(tenantId, projectId)).thenReturn(new BigDecimal("12.00"));
        when(usage.sumRawCostByProjectId(tenantId, projectId)).thenReturn(new BigDecimal("10.00"));

        ProjectEconomics economics = service.forProject(tenantId, projectId).forCreator();

        assertThat(economics.platform()).isNull();
        assertThat(economics.production()).isNull();
        assertThat(economics.creator().profit()).isEqualByComparingTo("-12.00");
    }

    @Test
    void anExtraReviewFeeStaysWithThePlatform() {
        when(creativePlanning.getProjectQuote(tenantId, projectId)).thenReturn(Optional.empty());
        when(reviewPayments.findByTenantIdAndProjectIdAndStatus(tenantId, projectId, "SUCCESS"))
                .thenReturn(List.of(payment("EXTRA_REVIEW", "100.00", "22.60")));
        when(usage.sumCostByProjectId(tenantId, projectId)).thenReturn(BigDecimal.ZERO);
        when(usage.sumRawCostByProjectId(tenantId, projectId)).thenReturn(BigDecimal.ZERO);

        ProjectEconomics economics = service.forProject(tenantId, projectId);

        assertThat(economics.creator().received()).isEqualByComparingTo("22.60");
        assertThat(economics.platform().reviewPaymentShare()).isEqualByComparingTo("100.00");
    }

    private ClientReviewPayment payment(String kind, String platformBase, String toCreator) {
        ClientReviewPayment payment = new ClientReviewPayment();
        payment.setKind(kind);
        payment.setPlatformBase(new BigDecimal(platformBase));
        payment.setCreatorAmount(new BigDecimal(toCreator));
        payment.setTotalAmount(new BigDecimal(platformBase).add(new BigDecimal(toCreator)));
        return payment;
    }
}
