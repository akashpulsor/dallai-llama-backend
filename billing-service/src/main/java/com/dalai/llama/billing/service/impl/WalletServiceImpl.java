package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import com.dalai.llama.billing.domain.event.WalletCreatedEvent;
import com.dalai.llama.billing.domain.exception.InsufficientBalanceException;
import com.dalai.llama.billing.domain.exception.WalletNotFoundException;
import com.dalai.llama.billing.kafka.producer.BillingEventProducer;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.service.TransactionService;
import com.dalai.llama.billing.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final TransactionService transactionService;
    private final BillingEventProducer eventProducer;

    @Override
    public void createWallet(UUID tenantId) {
        Wallet wallet = walletRepository.save(Wallet.createDefault(tenantId));

        eventProducer.publishWalletCreated(WalletCreatedEvent.builder()
                .tenantId(tenantId)
                .walletId(wallet.getId())
                .occurredAt(Instant.now())
                .build());

    }

    @Override
    public void deleteWallet(UUID tenantId) {
        walletRepository.deleteByTenantId(tenantId);
    }

    @Override
    @Transactional
    public void credit(UUID tenantId, BigDecimal amount, String reference) {
        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        wallet.credit(amount);
        walletRepository.save(wallet);

        transactionService.recordTransaction(
                tenantId, wallet.getId(), amount, TransactionType.RECHARGE, reference
        );
    }

    @Override
    @Transactional
    public void debit(UUID tenantId, BigDecimal amount, String reference) {
        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException(wallet.getBalance(), amount);
        }

        wallet.debit(amount);
        walletRepository.save(wallet);

        transactionService.recordTransaction(
                tenantId, wallet.getId(), amount.negate(), TransactionType.USAGE_DEDUCTION, reference
        );
    }

    @Override
    public BigDecimal getBalance(UUID tenantId) {
        return walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId))
                .getBalance();
    }
}
