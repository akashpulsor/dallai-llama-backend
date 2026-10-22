package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.CreatorPayoutAccount;
import com.dalai.llama.billing.domain.entity.enums.PayoutMethod;
import com.dalai.llama.billing.repository.CreatorPayoutAccountRepository;
import com.dalai.llama.billing.service.payment.PayoutGateway;
import com.dalai.llama.billing.service.payment.PayoutGateway.PayoutRejectedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where a creator is paid. Saving registers the bank account or UPI ID with RazorpayX as a fund
 * account (under one contact per creator) and keeps only what the creator needs to recognise it --
 * the full account number goes to RazorpayX and nowhere else. Changing it creates a new fund
 * account; payouts already sent stay with the old one.
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
        Optional<CreatorPayoutAccount> existing = accounts.findById(tenantId);
        String contactId;
        String fundAccountId;
        try {
            contactId = existing.map(CreatorPayoutAccount::getContactId)
                    .orElseGet(() -> payoutGateway.createContact(tenantId, valid.holderName()));
            fundAccountId = payoutGateway.createFundAccount(contactId, valid.method(), valid.holderName(),
                    valid.ifsc(), valid.accountNumber(), valid.vpa());
        } catch (PayoutRejectedException ex) {
            throw new IllegalArgumentException("Razorpay didn't accept these details -- check them and try again");
        }

        Instant now = Instant.now();
        CreatorPayoutAccount account = existing.orElseGet(() -> CreatorPayoutAccount.builder()
                .tenantId(tenantId).contactId(contactId).createdAt(now).build());
        account.setMethod(valid.method());
        account.setHolderName(valid.holderName());
        account.setIfsc(valid.ifsc());
        account.setAccountLast4(valid.accountNumber() == null ? null
                : valid.accountNumber().substring(valid.accountNumber().length() - 4));
        account.setVpa(valid.vpa());
        account.setFundAccountId(fundAccountId);
        account.setUpdatedAt(now);
        return accounts.save(account);
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
