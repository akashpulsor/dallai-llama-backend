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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final TransactionService transactionService;
    private final BillingEventProducer eventProducer;

    @Override
    @Transactional
    public Wallet createWallet(UUID tenantId) {
        return walletRepository.findByTenantId(tenantId)
                .map(wallet -> {
                    log.info("Wallet already exists for tenantId={}, skipping create", tenantId);
                    return wallet;
                })
                .orElseGet(() -> createMissingWallet(tenantId, false));
    }

    @Override
    @Transactional
    public Wallet getOrCreateWallet(UUID tenantId) {
        return walletRepository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    log.warn("Wallet missing for tenantId={}, creating default wallet", tenantId);
                    return createMissingWallet(tenantId, true);
                });
    }

    private Wallet createMissingWallet(UUID tenantId, boolean recovered) {
        try {
            Wallet wallet = walletRepository.save(Wallet.createDefault(tenantId));

            eventProducer.publishWalletCreated(WalletCreatedEvent.builder()
                    .tenantId(tenantId)
                    .walletId(wallet.getId())
                    .occurredAt(Instant.now())
                    .build());

            log.info("{} wallet id={} for tenantId={}",
                    recovered ? "Recovered missing" : "Created", wallet.getId(), tenantId);
            return wallet;
        } catch (DataIntegrityViolationException e) {
            log.info("Wallet was created concurrently for tenantId={}, loading existing wallet", tenantId);
            return walletRepository.findByTenantId(tenantId).orElseThrow(() -> e);
        }
    }

    @Override
    @Transactional
    public void deleteWallet(UUID tenantId) {
        if (!walletRepository.existsByTenantId(tenantId)) {
            log.info("No wallet found for tenantId={}, skipping delete", tenantId);
            return;
        }
        walletRepository.deleteByTenantId(tenantId);
        log.info("Deleted wallet for tenantId={}", tenantId);
    }

    @Override
    @Transactional
    public void credit(UUID tenantId, BigDecimal amount, String reference) {
        credit(tenantId, amount, reference, null, null);
    }

    @Override
    @Transactional
    public void credit(UUID tenantId, BigDecimal amount, String reference,
                       UUID subscriptionId, String idempotencyKey) {
        // Check idempotency
        if (idempotencyKey != null && transactionService.existsByIdempotencyKey(idempotencyKey)) {
            return; // Already processed
        }

        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        wallet.credit(amount);
        walletRepository.save(wallet);

        transactionService.recordTransaction(
                tenantId, wallet.getId(), amount, TransactionType.RECHARGE,
                reference, subscriptionId, idempotencyKey
        );

    }

    @Override
    @Transactional
    public void debit(UUID tenantId, BigDecimal amount, String reference) {
        debit(tenantId, amount, reference, null, null);
    }

    @Override
    @Transactional
    public void debit(UUID tenantId, BigDecimal amount, String reference, UUID subscriptionId) {
        debit(tenantId, amount, reference, subscriptionId, null);
    }

    @Override
    @Transactional
    public void debit(UUID tenantId, BigDecimal amount, String reference,
                      UUID subscriptionId, String idempotencyKey) {
        // Check idempotency
        if (idempotencyKey != null && transactionService.existsByIdempotencyKey(idempotencyKey)) {
            return; // Already processed
        }

        Wallet wallet = walletRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new WalletNotFoundException(tenantId));

        BigDecimal balanceBefore = wallet.getBalance();
        if (balanceBefore.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(balanceBefore, amount);
        }

        wallet.debit(amount);
        walletRepository.save(wallet);

        transactionService.recordTransaction(
                tenantId, wallet.getId(), amount.negate(), TransactionType.USAGE_DEDUCTION,
                reference, subscriptionId, idempotencyKey
        );
        log.info(
                "WALLET_DEBIT_AUDIT tenantId={} walletId={} amount={} balanceBefore={} balanceAfter={} reference={} subscriptionId={} idempotencyKey={}",
                tenantId,
                wallet.getId(),
                amount,
                balanceBefore,
                wallet.getBalance(),
                reference,
                subscriptionId,
                idempotencyKey
        );
    }

    @Override
    public BigDecimal getBalance(UUID tenantId) {
        return getOrCreateWallet(tenantId).getBalance();
    }
}
