package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.CreatorPayoutAccount;
import com.dalai.llama.billing.domain.entity.enums.PayoutMethod;
import com.dalai.llama.billing.repository.CreatorPayoutAccountRepository;
import com.dalai.llama.billing.service.payment.PayoutGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where a creator is paid. Saving only checks and keeps the details -- ops pays by hand from them
 * while RazorpayX payouts are off. The RazorpayX contact and fund account are registered the first
 * time an automatic payout needs them ({@link #fundAccountId}); changing the details clears the
 * fund account so the next automatic payout registers the new one.
 */
@Service
@RequiredArgsConstructor
public class CreatorPayoutAccountService {

    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("^\\d{9,18}$");
    private static final Pattern VPA = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$");

    private final CreatorPayoutAccountRepository accounts;
    private final PayoutGateway payoutGateway;

    @Transactional(readOnly = true)
    public Optional<CreatorPayoutAccount> find(UUID tenantId) {
        return accounts.findById(tenantId);
    }

    @Transactional
    public CreatorPayoutAccount save(UUID tenantId, Details details) {
        Details valid = details.validated();
        Instant now = Instant.now();
        CreatorPayoutAccount account = accounts.findById(tenantId).orElseGet(() -> CreatorPayoutAccount.builder()
                .tenantId(tenantId).createdAt(now).build());
        account.setMethod(valid.method());
        account.setHolderName(valid.holderName());
        account.setIfsc(valid.ifsc());
        account.setAccountNumber(valid.accountNumber());
        account.setAccountLast4(valid.accountNumber() == null ? null
                : valid.accountNumber().substring(valid.accountNumber().length() - 4));
        account.setVpa(valid.vpa());
        account.setFundAccountId(null);
        account.setUpdatedAt(now);
        return accounts.save(account);
    }

    /** The RazorpayX fund account to pay {@code account} into, registering it (and the creator's
     * contact) on first use.
     *
     * @throws PayoutGateway.PayoutRejectedException when RazorpayX refuses the details */
    @Transactional
    public String fundAccountId(CreatorPayoutAccount account) {
        if (account.getFundAccountId() != null) {
            return account.getFundAccountId();
        }
        if (account.getContactId() == null) {
            account.setContactId(payoutGateway.createContact(account.getTenantId(), account.getHolderName()));
        }
        account.setFundAccountId(payoutGateway.createFundAccount(account.getContactId(), account.getMethod(),
                account.getHolderName(), account.getIfsc(), account.getAccountNumber(), account.getVpa()));
        account.setUpdatedAt(Instant.now());
        accounts.save(account);
        return account.getFundAccountId();
    }

    /** What the creator types in: a bank account (holder, IFSC, number) or a UPI ID. */
    public record Details(PayoutMethod method, String holderName, String ifsc, String accountNumber, String vpa) {

        Details validated() {
            String name = holderName == null ? "" : holderName.trim();
            if (name.length() < 3 || name.length() > 120) {
                throw new IllegalArgumentException("Enter the account holder's name as the bank has it");
            }
            if (method == PayoutMethod.UPI) {
                String address = vpa == null ? "" : vpa.trim();
                if (!VPA.matcher(address).matches()) {
                    throw new IllegalArgumentException("Enter a UPI ID like name@bank");
                }
                return new Details(method, name, null, null, address);
            }
            if (method != PayoutMethod.BANK_ACCOUNT) {
                throw new IllegalArgumentException("Choose a bank account or UPI");
            }
            String code = ifsc == null ? "" : ifsc.trim().toUpperCase(Locale.ROOT);
            String number = accountNumber == null ? "" : accountNumber.replaceAll("\\s", "");
            if (!IFSC.matcher(code).matches()) {
                throw new IllegalArgumentException("Enter an 11-character IFSC like HDFC0001234");
            }
            if (!ACCOUNT_NUMBER.matcher(number).matches()) {
                throw new IllegalArgumentException("Enter the account number (9 to 18 digits)");
            }
            return new Details(method, name, code, number, null);
        }
    }
}
