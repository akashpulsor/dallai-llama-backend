package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.Payment;
import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.entity.enums.PaymentStatus;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.domain.exception.InsufficientBalanceException;
import com.dalai.llama.billing.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
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

class BriefPaymentTransferServiceTest {

    private final UUID house = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final UUID requirement = UUID.randomUUID();
    private final UUID creatorWallet = UUID.randomUUID();

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final WalletService wallets = mock(WalletService.class);
    private final BriefPaymentTransferService service = new BriefPaymentTransferService(payments, wallets);

    @BeforeEach
    void wallets() {
        Wallet wallet = mock(Wallet.class);
        when(wallet.getId()).thenReturn(creatorWallet);
        when(wallets.getOrCreateWallet(creator)).thenReturn(wallet);
    }

    @Test
    void aPaidBriefsPaymentAndItsWalletCreditGoToTheCreatorWhoTookIt() {
        Payment paid = payment(house, PaymentStatus.SUCCESS, "RAZORPAY", "300");
        Payment abandoned = payment(house, PaymentStatus.FAILED, "RAZORPAY", "300");
        when(payments.findByProjectRequirementIdOrderByCreatedAtDesc(requirement)).thenReturn(List.of(paid, abandoned));

        service.transfer(house, requirement, creator);

        assertThat(paid.getTenantId()).isEqualTo(creator);
        assertThat(paid.getWalletId()).isEqualTo(creatorWallet);
        assertThat(abandoned.getTenantId()).isEqualTo(creator);
        verify(wallets).debit(eq(house), eq(new BigDecimal("300")), eq(TransactionType.ADJUSTMENT_DEBIT), anyString(), isNull(),
                eq("BRIEF_TRANSFER_OUT:" + paid.getId() + ":" + creator), anyString(), isNull());
        verify(wallets).credit(eq(creator), eq(new BigDecimal("300")), eq(TransactionType.ADJUSTMENT_CREDIT), anyString(), isNull(),
                eq("BRIEF_TRANSFER_IN:" + paid.getId() + ":" + creator), anyString());
        // Only the captured payment ever credited a wallet.
        verify(wallets, never()).debit(any(), any(), any(), any(), any(), eq("BRIEF_TRANSFER_OUT:" + abandoned.getId() + ":" + creator), any(), any());
    }

    @Test
    void repeatingIt_orAWalletFundedPayment_movesNoMoney() {
        Payment alreadyMoved = payment(creator, PaymentStatus.SUCCESS, "RAZORPAY", "300");
        Payment fromWallet = payment(house, PaymentStatus.SUCCESS, "WALLET", "300");
        when(payments.findByProjectRequirementIdOrderByCreatedAtDesc(requirement)).thenReturn(List.of(alreadyMoved, fromWallet));

        service.transfer(house, requirement, creator);

        assertThat(fromWallet.getTenantId()).isEqualTo(creator);
        verify(wallets, never()).debit(any(), any(), any(), any(), any(), any(), any(), any());
        verify(wallets, never()).credit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void whenTheFirstAccountAlreadySpentTheCredit_nothingMoves() {
        Payment paid = payment(house, PaymentStatus.SUCCESS, "RAZORPAY", "300");
        when(payments.findByProjectRequirementIdOrderByCreatedAtDesc(requirement)).thenReturn(List.of(paid));
        doThrow(new InsufficientBalanceException(BigDecimal.TEN, new BigDecimal("300")))
                .when(wallets).debit(any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.transfer(house, requirement, creator)).isInstanceOf(InsufficientBalanceException.class);
        assertThat(paid.getTenantId()).isEqualTo(house);
    }

    private Payment payment(UUID tenant, PaymentStatus status, String gateway, String amount) {
        return Payment.builder().id(UUID.randomUUID()).tenantId(tenant).projectRequirementId(requirement).status(status)
                .gateway(gateway).gatewayOrderId("order_" + UUID.randomUUID()).amount(new BigDecimal(amount)).currency("INR").build();
    }
}
