package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.dto.AdminCreditRequest;
import com.dalai.llama.billing.dto.AdminWalletView;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.service.WalletService;
import jakarta.validation.Valid;
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

    @GetMapping("/wallets/{tenantId}")
    public ResponseEntity<AdminWalletView> wallet(@PathVariable UUID tenantId) {
        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No wallet for tenant " + tenantId));
        return ResponseEntity.ok(AdminWalletView.from(wallet));
    }

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
