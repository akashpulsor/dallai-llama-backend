package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.service.revenue.BillingClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
    private final PublicProjectService service = new PublicProjectService(projectService, projectLockService,
            null, null, null, null, null, null, null, null, null, billingClient, null, null, null, null, null);

    @Test
    void refusesToLockWithoutPaymentWhileABalanceIsDue() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(projectService.resolveByClientReviewToken("tok")).thenReturn(new ProjectService.ProjectIdentity(tenantId, projectId));
        when(billingClient.quote(tenantId, projectId)).thenReturn(quote("2587.50"));

        assertThatThrownBy(() -> service.lockSettled("tok")).isInstanceOf(PreProductionException.class);
        verify(projectLockService, never()).lock(any(), any());
    }

    @Test
    void onlyAZeroBalanceCountsAsSettled() {
        assertThat(quote("0.00").settled()).isTrue();
        assertThat(quote("0.01").settled()).isFalse();
    }

    private static BillingClient.Quote quote(String total) {
        return new BillingClient.Quote(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(total), "INR",
                new BigDecimal("15"), new BigDecimal("3450.00"), new BigDecimal("862.50"));
    }
}
