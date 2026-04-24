package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.Transaction;
import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.exception.WalletNotFoundException;
import com.dalai.llama.billing.dto.mapper.BillingMapper;
import com.dalai.llama.billing.dto.request.RechargeWalletRequest;
import com.dalai.llama.billing.dto.response.TransactionResponse;
import com.dalai.llama.billing.dto.response.WalletResponse;
import com.dalai.llama.billing.repository.TransactionRepository;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/billing/{tenantId}/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Wallet management APIs")
public class WalletController {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final PaymentService paymentService;
    private final BillingMapper mapper;

    @GetMapping
    @Operation(summary = "Get wallet details", description = "Retrieve wallet balance and details for a tenant")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable UUID tenantId) {

        log.info("Fetching wallet for tenant {}", tenantId);
        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        return ResponseEntity.ok(WalletResponse.builder()
                .walletId(wallet.getId())
                .tenantId(wallet.getTenantId())
                .balance(wallet.getBalance())
                .currency(wallet.getCurrency())
                .creditLimit(wallet.getCreditLimit())
                .lowBalanceThreshold(wallet.getLowBalanceThreshold())
                .autoRechargeEnabled(wallet.getAutoRechargeEnabled())
                .autoRechargeThreshold(wallet.getAutoRechargeThreshold())
                .autoRechargeAmount(wallet.getAutoRechargeAmount())
                .lastRechargedAt(wallet.getLastRechargedAt())
                .build());
    }


    @PostMapping("/recharge")
    @Operation(summary = "Initiate wallet recharge", description = "Create a payment order to recharge wallet")
    public ResponseEntity<RechargeResponse> rechargeWallet(
            @PathVariable UUID tenantId,
            @Valid @RequestBody RechargeWalletRequest request
    ) {
        UUID paymentId = paymentService.createPayment(
                tenantId,
                request.getCurrency(),
                request.getAmount(),
                "Wallet Recharge",
                request.getSubscriptionId()
        );

        return ResponseEntity.ok(RechargeResponse.builder()
                .paymentId(paymentId)
                .amount(request.getAmount())
                .status("PENDING")
                .message("Payment order created. Complete payment to credit wallet.")
                .build());
    }

    @GetMapping("/transactions")
    @Operation(summary = "List transactions", description = "Get paginated list of wallet transactions")
    public ResponseEntity<Page<TransactionResponse>> getTransactions(
            @PathVariable UUID tenantId,
            Pageable pageable
    ) {
        List<Transaction> transactions = transactionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);

        List<TransactionResponse> responses = transactions.stream()
                .map(tx -> TransactionResponse.builder()
                        .id(tx.getId())
                        .type(tx.getType().name())
                        .amount(tx.getAmount())
                        .balanceBefore(tx.getBalanceBefore())
                        .balanceAfter(tx.getBalanceAfter())
                        .reference(tx.getReference())
                        .description(tx.getDescription())
                        .createdAt(tx.getCreatedAt())
                        .build())
                .toList();

        // Simple pagination for now
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), responses.size());

        return ResponseEntity.ok(new PageImpl<>(
                responses.subList(start, end),
                pageable,
                responses.size()
        ));
    }

    @GetMapping("/transactions/{transactionId}")
    @Operation(summary = "Get transaction details", description = "Retrieve specific transaction details")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable UUID tenantId,
            @PathVariable UUID transactionId
    ) {
        Transaction tx = transactionRepository.findById(transactionId)
                .filter(t -> t.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

        return ResponseEntity.ok(TransactionResponse.builder()
                .id(tx.getId())
                .type(tx.getType().name())
                .amount(tx.getAmount())
                .balanceBefore(tx.getBalanceBefore())
                .balanceAfter(tx.getBalanceAfter())
                .reference(tx.getReference())
                .description(tx.getDescription())
                .createdAt(tx.getCreatedAt())
                .build());
    }

    @lombok.Builder
    @lombok.Getter
    public static class RechargeResponse {
        private UUID paymentId;
        private java.math.BigDecimal amount;
        private String status;
        private String message;
    }
}
