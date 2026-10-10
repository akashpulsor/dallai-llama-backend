package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.dto.ProjectView;
import com.dalai.llama.preprod.service.creativeplanning.CreativePlanningClient;
import com.dalai.llama.preprod.service.revenue.BillingClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicProjectServiceLockSettledTest {

    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectLockService projectLockService = mock(ProjectLockService.class);
    private final BillingClient billingClient = mock(BillingClient.class);
    private final CreativePlanningClient creativePlanning = mock(CreativePlanningClient.class);
    private final PublicProjectService service = new PublicProjectService(projectService, projectLockService,
            null, null, null, null, null, null, null, null, null, billingClient, null, null, null, null, null, creativePlanning, null);

    @Test
    void refusesToLockWithoutPaymentWhileABalanceIsDue() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(projectService.resolveByClientReviewToken("tok")).thenReturn(new ProjectService.ProjectIdentity(tenantId, projectId));
        when(billingClient.quote(tenantId, projectId)).thenReturn(quote("2587.50"));

        assertThatThrownBy(() -> service.lockSettled("tok", "2026-10")).isInstanceOf(PreProductionException.class);
        verify(projectLockService, never()).lock(any(), any());
        // Nothing was paid, so no marketing consent is recorded either.
        verify(projectService, never()).recordMarketingConsent(any(), any(), any());
    }

    @Test
    void aSettledLockRecordsTheMarketingConsentBeforeLocking() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(projectService.resolveByClientReviewToken("tok")).thenReturn(new ProjectService.ProjectIdentity(tenantId, projectId));
        when(billingClient.quote(tenantId, projectId)).thenReturn(quote("0.00"));

        try {
            service.lockSettled("tok", "2026-10");
        } catch (RuntimeException ignoredViewRendering) {
            // view(token) needs collaborators this test doesn't wire; the order of the two calls is the point.
        }

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(projectService, projectLockService);
        order.verify(projectService).recordMarketingConsent(tenantId, projectId, "2026-10");
        order.verify(projectLockService).lock(tenantId, projectId);
    }

    @Test
    void startingTheLockPaymentRecordsConsent() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(projectService.resolveByClientReviewToken("tok")).thenReturn(new ProjectService.ProjectIdentity(tenantId, projectId));

        service.startLockPayment("tok", "2026-10");

        verify(projectService).recordMarketingConsent(tenantId, projectId, "2026-10");
        verify(billingClient).createOrder(tenantId, projectId, "tok");
    }

    @Test
    void theNextBriefOpensOnlyAfterThisVideoIsLocked() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(projectService.resolveByClientReviewToken("tok")).thenReturn(new ProjectService.ProjectIdentity(tenantId, projectId));
        when(projectService.get(tenantId, projectId)).thenReturn(project(projectId, null));

        assertThatThrownBy(() -> service.startNextBrief("tok")).isInstanceOf(PreProductionException.class);
        verify(creativePlanning, never()).startNextBrief(any(), any());

        when(projectService.get(tenantId, projectId)).thenReturn(project(projectId, OffsetDateTime.now()));
        when(creativePlanning.startNextBrief(tenantId, projectId)).thenReturn(new CreativePlanningClient.NextBrief("next"));
        assertThat(service.startNextBrief("tok").shareToken()).isEqualTo("next");
    }

    private static ProjectView project(UUID projectId, OffsetDateTime clientLockedAt) {
        return new ProjectView(projectId, "City Professional", null, null, null, null, 2, true, false, clientLockedAt, false);
    }

    @Test
    void onlyAZeroBalanceCountsAsSettled() {
        assertThat(quote("0.00").settled()).isTrue();
        assertThat(quote("0.01").settled()).isFalse();
    }

    private static BillingClient.Quote quote(String total) {
        return new BillingClient.Quote(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(total), "INR",
                new BigDecimal("15"), new BigDecimal("3450.00"), new BigDecimal("862.50"), null);
    }
}
