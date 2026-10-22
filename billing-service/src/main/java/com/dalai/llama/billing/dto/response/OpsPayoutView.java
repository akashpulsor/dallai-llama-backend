package com.dalai.llama.billing.dto.response;

import com.dalai.llama.billing.domain.entity.CreatorPayoutAccount;
import com.dalai.llama.billing.domain.entity.enums.PayoutMethod;
import com.dalai.llama.billing.service.CreatorPayoutService;

/** Ops' row for one payout: the payout, and the full bank / UPI details to pay it by hand
 * ({@code payee} is null until the creator adds them). Never served to a creator. */
public record OpsPayoutView(CreatorPayoutView payout, Payee payee) {

    public record Payee(PayoutMethod method, String holderName, String ifsc, String accountNumber, String vpa) {}

    public static OpsPayoutView of(CreatorPayoutService.OpsPayout row) {
        CreatorPayoutAccount account = row.payee();
        return new OpsPayoutView(CreatorPayoutView.of(row.payout()), account == null ? null
                : new Payee(account.getMethod(), account.getHolderName(), account.getIfsc(),
                        account.getAccountNumber(), account.getVpa()));
    }
}
