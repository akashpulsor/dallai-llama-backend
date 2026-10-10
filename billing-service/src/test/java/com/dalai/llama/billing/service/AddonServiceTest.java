package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.domain.exception.InsufficientBalanceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddonServiceTest {

    private final WalletService wallet = mock(WalletService.class);
    private final BillingStateService state = mock(BillingStateService.class);
    private final AddonService service = new AddonService(wallet, state, new BigDecimal("499"), 500);

    @Test
    void buyingAMailPackDebitsThePriceBillingOwnsWithTheCallersIdempotencyKey() {
        UUID tenant = UUID.randomUUID();
        when(wallet.getBalance(tenant)).thenReturn(new BigDecimal("1501"));

        AddonService.AddonPurchase bought = service.purchase(tenant, AddonService.AddonCode.OUTREACH_MAIL_PACK, "pack-1");

        verify(wallet).debit(eq(tenant), eq(new BigDecimal("499")), eq(TransactionType.ADDON), anyString(), isNull(),
                eq("addon:pack-1"), anyString(), isNull());
        verify(state).evaluateState(tenant);
        assertThat(bought.quantity()).isEqualTo(500);
        assertThat(bought.balanceAfter()).isEqualByComparingTo("1501");
    }

    @Test
    void anEmptyWalletBuysNothing() {
        UUID tenant = UUID.randomUUID();
        doThrow(new InsufficientBalanceException(BigDecimal.TEN, new BigDecimal("499")))
                .when(wallet).debit(any(), any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.purchase(tenant, AddonService.AddonCode.OUTREACH_MAIL_PACK, "pack-2"))
                .isInstanceOf(InsufficientBalanceException.class);
        verify(state, never()).evaluateState(tenant);
    }
}
