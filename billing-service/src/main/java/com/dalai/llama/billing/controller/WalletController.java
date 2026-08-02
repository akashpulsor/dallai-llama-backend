package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.domain.entity.Transaction;
import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.dto.request.RechargeWalletRequest;
import com.dalai.llama.billing.dto.response.TransactionResponse;
import com.dalai.llama.billing.dto.response.WalletResponse;
import com.dalai.llama.billing.repository.TransactionRepository;
import com.dalai.llama.billing.service.PaymentService;
import com.dalai.llama.billing.service.PaymentService.PaymentOrderResult;
import com.dalai.llama.billing.service.WalletService;
import com.fasterxml.jackson.annotation.JsonProperty;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/billing/{tenantId}/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Wallet management APIs")
public class WalletController {

    private final TransactionRepository transactionRepository;
    private final PaymentService paymentService;
    private final WalletService walletService;

    @GetMapping
    @Operation(summary = "Get wallet details", description = "Retrieve wallet balance and details for a tenant")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable UUID tenantId) {

        log.info("Fetching wallet for tenant {}", tenantId);
        Wallet wallet = walletService.getOrCreateWallet(tenantId);

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
        PaymentOrderResult order = paymentService.createPaymentOrder(
                tenantId,
                request.getCurrency(),
                request.getAmount(),
                "Wallet Recharge",
                request.getSubscriptionId()
        );

        int amountPaise = toPaise(order.amount());
        return ResponseEntity.ok(RechargeResponse.builder()
                .paymentId(order.paymentId())
                .gatewayOrderId(order.gatewayOrderId())
                .orderId(order.gatewayOrderId())
                .amount(order.amount())
                .amountPaise(amountPaise)
                .currency(order.currency())
                .keyId(order.keyId())
                .checkoutDetails(CheckoutDetails.builder()
                        .key(order.keyId())
                        .keyId(order.keyId())
                        .orderId(order.gatewayOrderId())
                        .amount(amountPaise)
                        .currency(order.currency())
                        .name("Dalai Llama Platform")
                        .description("Wallet recharge")
                        .build())
                .status(order.status())
                .message("Payment order created. Complete payment to credit wallet.")
                .build());
    }

    private int toPaise(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
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
        private String gatewayOrderId;
        @JsonProperty("order_id")
        private String orderId;
        private BigDecimal amount;
        private Integer amountPaise;
        private String currency;
        private String keyId;
        private CheckoutDetails checkoutDetails;
        private String status;
        private String message;
    }

    @lombok.Builder
    @lombok.Getter
    public static class CheckoutDetails {
        private String key;
        private String keyId;
        @JsonProperty("order_id")
        private String orderId;
        private Integer amount;
        private String currency;
        private String name;
        private String description;
    }
}
