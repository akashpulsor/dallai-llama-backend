package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.Transaction;
import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.domain.exception.WalletNotFoundException;
import com.dalai.llama.billing.repository.TransactionRepository;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;

    @Override
    @Transactional
    public void recordTransaction(
            UUID tenantId,
            UUID walletId,
            BigDecimal amount,
            TransactionType type,
            String reference
    ) {
        // Get wallet to record balance before/after
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        // Calculate balance before (current balance is already updated by wallet service)
        BigDecimal balanceAfter = wallet.getBalance();
        BigDecimal balanceBefore = balanceAfter.subtract(amount);

        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .walletId(walletId)
                .amount(amount)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .type(type)
                .reference(reference)
                .description(generateDescription(type, reference))
                .createdAt(Instant.now())
                .build();

        transactionRepository.save(tx);
    }

    private String generateDescription(TransactionType type, String reference) {
        return switch (type) {
            case RECHARGE -> "Wallet recharge";
            case USAGE_DEDUCTION -> "Usage charge: " + (reference != null ? reference : "");
            case ADJUSTMENT_CREDIT -> "Credit adjustment";
            case ADJUSTMENT_DEBIT -> "Debit adjustment";
            case REFUND -> "Payment refund";
            case DID_RENTAL -> "DID rental charge";
            case SUBSCRIPTION -> "Subscription fee";
        };
    }
}
