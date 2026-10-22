package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.CreatorPayoutAccount;
import com.dalai.llama.billing.domain.entity.enums.PayoutMethod;
import com.dalai.llama.billing.repository.CreatorPayoutAccountRepository;
import com.dalai.llama.billing.service.CreatorPayoutAccountService.Details;
import com.dalai.llama.billing.service.payment.PayoutGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreatorPayoutAccountServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final CreatorPayoutAccountRepository accounts = mock(CreatorPayoutAccountRepository.class);
    private final PayoutGateway gateway = mock(PayoutGateway.class);
    private final CreatorPayoutAccountService service = new CreatorPayoutAccountService(accounts, gateway);

    @Test
    void aBankAccountGoesToRazorpayAndOnlyItsLastFourDigitsAreKept() {
        when(accounts.findById(tenantId)).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.createContact(tenantId, "Asha Rao")).thenReturn("cont_1");
        when(gateway.createFundAccount("cont_1", PayoutMethod.BANK_ACCOUNT, "Asha Rao", "HDFC0001234", "50100012345678", null))
                .thenReturn("fa_1");

        CreatorPayoutAccount saved = service.save(tenantId,
                new Details(PayoutMethod.BANK_ACCOUNT, " Asha Rao ", "hdfc0001234", "5010 0012 3456 78", null));

        assertThat(saved.getAccountLast4()).isEqualTo("5678");
        assertThat(saved.getIfsc()).isEqualTo("HDFC0001234");
        assertThat(saved.getFundAccountId()).isEqualTo("fa_1");
        assertThat(saved.getContactId()).isEqualTo("cont_1");
    }

    @Test
    void changingTheAccountKeepsTheSameRazorpayContact() {
        when(accounts.findById(tenantId)).thenReturn(Optional.of(CreatorPayoutAccount.builder().tenantId(tenantId)
                .method(PayoutMethod.BANK_ACCOUNT).holderName("Asha Rao").contactId("cont_1").fundAccountId("fa_1")
                .createdAt(Instant.now()).build()));
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.createFundAccount("cont_1", PayoutMethod.UPI, "Asha Rao", null, null, "asha@okhdfc")).thenReturn("fa_2");

        CreatorPayoutAccount saved = service.save(tenantId, new Details(PayoutMethod.UPI, "Asha Rao", null, null, "asha@okhdfc"));

        verify(gateway, never()).createContact(any(), anyString());
        assertThat(saved.getFundAccountId()).isEqualTo("fa_2");
        assertThat(saved.getAccountLast4()).isNull();
        assertThat(saved.getVpa()).isEqualTo("asha@okhdfc");
    }

    @Test
    void badDetailsAreRefusedBeforeAnythingReachesRazorpay() {
        assertThatThrownBy(() -> service.save(tenantId, new Details(PayoutMethod.BANK_ACCOUNT, "Asha Rao", "HDFC1234", "50100012345678", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IFSC");
        assertThatThrownBy(() -> service.save(tenantId, new Details(PayoutMethod.BANK_ACCOUNT, "Asha Rao", "HDFC0001234", "12ab", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("account number");
        assertThatThrownBy(() -> service.save(tenantId, new Details(PayoutMethod.UPI, "Asha Rao", null, null, "not-a-vpa")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UPI");
        verifyNoInteractions(gateway, accounts);
    }

    @Test
    void razorpayRefusingTheDetailsIsTheCreatorsToFix() {
        when(accounts.findById(tenantId)).thenReturn(Optional.empty());
        when(gateway.createContact(any(), anyString())).thenReturn("cont_1");
        when(gateway.createFundAccount(anyString(), any(), anyString(), any(), any(), any()))
                .thenThrow(new PayoutGateway.PayoutRejectedException("bad ifsc"));

        assertThatThrownBy(() -> service.save(tenantId, new Details(PayoutMethod.BANK_ACCOUNT, "Asha Rao", "HDFC0001234", "50100012345678", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
