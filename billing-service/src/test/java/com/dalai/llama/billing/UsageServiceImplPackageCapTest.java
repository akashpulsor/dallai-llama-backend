package com.dalai.llama.billing;

import com.dalai.llama.billing.domain.entity.UsageRecord;
import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.entity.enums.BillingUnit;
import com.dalai.llama.billing.domain.entity.enums.UsageMetric;
import com.dalai.llama.billing.repository.UsageRecordRepository;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.service.BillableUsageRequest;
import com.dalai.llama.billing.service.BillingStateService;
import com.dalai.llama.billing.service.TransactionService;
import com.dalai.llama.billing.service.WalletService;
import com.dalai.llama.billing.service.impl.UsageServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageServiceImplPackageCapTest {

    @Test
    void capsLateIncludedUsageAtRemainingPackageBalance() {
        UsageRecordRepository usageRepository = mock(UsageRecordRepository.class);
        WalletRepository walletRepository = mock(WalletRepository.class);
        WalletService walletService = mock(WalletService.class);
        BillingStateService billingStateService = mock(BillingStateService.class);
        TransactionService transactionService = mock(TransactionService.class);
        UsageServiceImpl service = new UsageServiceImpl(
                usageRepository,
                walletRepository,
                walletService,
                billingStateService,
                transactionService
        );
        ReflectionTestUtils.setField(service, "conversionRatesConfig", "INR_INR=1,USD_INR=95");
        ReflectionTestUtils.setField(service, "aiShortStarterPriceInr", new BigDecimal("5999"));

        UUID tenantId = UUID.randomUUID();
        UUID packageScopeId = UUID.randomUUID();
        when(walletRepository.findByTenantIdForUpdate(tenantId)).thenReturn(Optional.of(
                Wallet.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .currency("INR")
                        .balance(new BigDecimal("10000"))
                        .build()
        ));
        when(usageRepository.sumCostByPackageScope(
                tenantId,
                packageScopeId,
                List.of("CREATOR_VIDEO_PACKAGE_USAGE", "CREATOR_VIDEO_PACKAGE")
        )).thenReturn(new BigDecimal("5900"));

        service.recordBillableUsage(new BillableUsageRequest(
                tenantId,
                null,
                UsageMetric.AI_VIDEO_SECONDS,
                BigDecimal.ONE,
                BillingUnit.SECOND,
                new BigDecimal("300"),
                new BigDecimal("300"),
                "CREATOR_VIDEO_PACKAGE_USAGE",
                packageScopeId,
                "Late fal.ai usage",
                null,
                UUID.randomUUID().toString(),
                "INR",
                Instant.now()
        ));

        ArgumentCaptor<UsageRecord> recordCaptor = ArgumentCaptor.forClass(UsageRecord.class);
        verify(usageRepository).save(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getTotalCost()).isEqualByComparingTo("99.0000");
        verify(walletService).debit(
                eq(tenantId),
                eq(new BigDecimal("99.0000")),
                eq("USAGE:AI_VIDEO_SECONDS:CREATOR_VIDEO_PACKAGE_USAGE"),
                eq(null),
                any(String.class)
        );
    }
}
