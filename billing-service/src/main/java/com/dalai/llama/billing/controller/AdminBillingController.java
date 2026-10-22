package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.dto.AdminCreditRequest;
import com.dalai.llama.billing.dto.AdminWalletView;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.dto.response.CreatorPayoutView;
import com.dalai.llama.billing.dto.response.OpsPayoutView;
import com.dalai.llama.billing.service.CreatorPayoutService;
import com.dalai.llama.billing.service.EmailService;
import com.dalai.llama.billing.service.ProjectEconomics;
import com.dalai.llama.billing.service.ProjectEconomicsService;
import com.dalai.llama.billing.service.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Wallet + subscription operations for the ops dashboard's Wallets tab. Perimeter is the ops-
 * dashboard oauth2-proxy AuthorizationPolicy on the Istio gateway ({@code /api/v1/internal/**}
 * has permitAll in Spring); this controller does no extra role check.
 *
 * <p>Wallet credit reuses {@link WalletService#credit(UUID, java.math.BigDecimal, TransactionType,
 * String, UUID, String, String)} with type=ADJUSTMENT_CREDIT so the transaction ledger
 * distinguishes a manual operator adjustment from a Razorpay-driven RECHARGE. The reference
 * string is required from the caller and lands verbatim in {@code transactions.reference} --
 * that column is the audit trail an auditor will read six months from now to understand why an
 * off-payment-flow credit appeared.
 */
@RestController
@RequestMapping("/api/v1/internal/admin/billing")
@RequiredArgsConstructor
public class AdminBillingController {

    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final EmailService emailService;
    private final ProjectEconomicsService projectEconomicsService;
    private final CreatorPayoutService creatorPayoutService;

    /** Ops view of creator payouts across every tenant, newest first (last 200), each with the
     * bank / UPI details to pay it by hand. */
    @GetMapping("/payouts")
    public ResponseEntity<List<OpsPayoutView>> payouts() {
        return ResponseEntity.ok(creatorPayoutService.recentForOps().stream().map(OpsPayoutView::of).toList());
    }

    /** Ops paid a READY payout by hand; records the transfer's UTR and marks it PAID. */
    @PostMapping("/payouts/{payoutId}/mark-paid")
    public ResponseEntity<CreatorPayoutView> markPayoutPaid(@PathVariable UUID payoutId, @RequestBody MarkPaidRequest request) {
        return ResponseEntity.ok(CreatorPayoutView.of(creatorPayoutService.markPaid(payoutId, request.utr())));
    }

    public record MarkPaidRequest(String utr) {}

    /** Sends a FAILED payout again; the amount is worked out afresh on the next dispatcher run. */
    @PostMapping("/payouts/{payoutId}/retry")
    public ResponseEntity<CreatorPayoutView> retryPayout(@PathVariable UUID payoutId) {
        return ResponseEntity.ok(CreatorPayoutView.of(creatorPayoutService.retry(payoutId)));
    }

    /** Ops view of every project for a tenant: provider cost vs what was charged vs what the
     * client paid, with both the creator's and the platform's profit. */
    @GetMapping("/tenants/{tenantId}/projects/economics")
    public ResponseEntity<List<ProjectEconomics>> projectEconomics(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(projectEconomicsService.forTenant(tenantId));
    }

    @GetMapping("/wallets/{tenantId}")
    public ResponseEntity<AdminWalletView> wallet(@PathVariable UUID tenantId) {
        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No wallet for tenant " + tenantId));
        return ResponseEntity.ok(AdminWalletView.from(wallet));
    }

    /** Manual SMTP smoke test for the ops dashboard. Sends a one-line plain-text email to the
     * given address using the currently-configured spring.mail credentials -- the fastest way to
     * confirm that a values-secret.yaml change actually landed and Hostinger accepted the auth
     * without waiting for a real payment/webhook to fire. Returns 200 whether the send succeeded
     * or not (EmailService swallows SMTP failures by design); check the pod logs for the
     * "Sent email" / "Failed to send email" line to see the outcome. */
    @PostMapping("/email-test")
    public ResponseEntity<Void> emailTest(@Valid @RequestBody EmailTestRequest request) {
        emailService.send(request.to(), request.subject(), request.body());
        return ResponseEntity.accepted().build();
    }

    public record EmailTestRequest(@NotBlank @Email String to, @NotBlank String subject, @NotBlank String body) {}

    @PostMapping("/wallets/{tenantId}/credit")
    public ResponseEntity<AdminWalletView> credit(@PathVariable UUID tenantId,
                                                  @Valid @RequestBody AdminCreditRequest request) {
        // ADJUSTMENT_CREDIT keeps this out of the RECHARGE bucket a Razorpay top-up uses,
        // and out of RATE_LIMITED sweeps that look at recharge cadence for auto-recharge logic.
        walletService.credit(tenantId, request.amount(), TransactionType.ADJUSTMENT_CREDIT,
                request.reference(), null, null, null);
        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Wallet vanished after credit for tenant " + tenantId));
        return ResponseEntity.ok(AdminWalletView.from(wallet));
    }
}
