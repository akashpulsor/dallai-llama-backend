package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.CreatorPayoutAccount;
import com.dalai.llama.billing.domain.entity.enums.PayoutMethod;
import com.dalai.llama.billing.dto.response.CreatorPayoutView;
import com.dalai.llama.billing.service.CreatorPayoutAccountService;
import com.dalai.llama.billing.service.CreatorPayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The creator's side of payouts: where they're paid, and what each project paid them. */
@RestController
@RequestMapping("/api/v1/billing/{tenantId}")
@RequiredArgsConstructor
@Tag(name = "Creator payouts")
public class CreatorPayoutController {

    private final CreatorPayoutAccountService accountService;
    private final CreatorPayoutService payoutService;

    @GetMapping("/payout-account")
    @Operation(summary = "Where this creator is paid (204 if not set yet)")
    public ResponseEntity<PayoutAccountView> account(@PathVariable UUID tenantId) {
        return accountService.find(tenantId)
                .map(account -> ResponseEntity.ok(PayoutAccountView.of(account)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/payout-account")
    @Operation(summary = "Set the bank account or UPI ID payouts go to")
    public ResponseEntity<PayoutAccountView> saveAccount(@PathVariable UUID tenantId,
                                                         @RequestBody CreatorPayoutAccountService.Details details) {
        return ResponseEntity.ok(PayoutAccountView.of(accountService.save(tenantId, details)));
    }

    @GetMapping("/payouts")
    @Operation(summary = "Every project payout, newest first")
    public ResponseEntity<List<CreatorPayoutView>> payouts(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(payoutService.forTenant(tenantId).stream().map(CreatorPayoutView::of).toList());
    }

    /** Only what the creator needs to recognise the account -- never the RazorpayX ids. */
    public record PayoutAccountView(PayoutMethod method, String holderName, String ifsc, String accountLast4,
                                    String vpa, Instant updatedAt) {
        static PayoutAccountView of(CreatorPayoutAccount account) {
            return new PayoutAccountView(account.getMethod(), account.getHolderName(), account.getIfsc(),
                    account.getAccountLast4(), account.getVpa(), account.getUpdatedAt());
        }
    }
}
