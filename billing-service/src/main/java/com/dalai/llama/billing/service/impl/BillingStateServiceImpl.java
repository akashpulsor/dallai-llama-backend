package com.dalai.llama.billing.service.impl;

import com.dalai.llama.billing.domain.entity.BillingState;
import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.entity.enums.BillingStateType;
import com.dalai.llama.billing.kafka.producer.BillingEventProducer;
import com.dalai.llama.billing.repository.BillingStateRepository;
import com.dalai.llama.billing.repository.WalletRepository;
import com.dalai.llama.billing.service.BillingStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingStateServiceImpl implements BillingStateService {

    private final BillingStateRepository billingStateRepository;
    private final WalletRepository walletRepository;
    private final BillingEventProducer eventProducer;

    @Override
    @Transactional
    public void evaluateState(UUID tenantId) {
        Wallet wallet = walletRepository.findByTenantId(tenantId).orElseThrow();
        BillingState state = billingStateRepository.findByTenantId(tenantId)
                .orElseGet(() -> BillingState.createDefault(tenantId));

        BillingStateType previous = state.getState();

        if (wallet.getBalance().signum() <= 0 && state.getState() == BillingStateType.ACTIVE) {
            state.enterGrace(Instant.now().plus(7, ChronoUnit.DAYS));
        }

        if (previous != state.getState()) {
            billingStateRepository.save(state);
            eventProducer.publishBillingStateChanged(
                    state.toEvent(previous)
            );
        }
    }

    @Override
    @Transactional
    public void transitionExpiredGraceTenants() {
        billingStateRepository
                .findByStateAndGraceExpiresAtBefore(BillingStateType.GRACE, Instant.now())
                .forEach(state -> {
                    BillingStateType previous = state.getState();
                    state.block("Grace period expired");
                    billingStateRepository.save(state);
                    eventProducer.publishBillingStateChanged(
                            state.toEvent(previous)
                    );
                });
    }

    @Override
    public void detectLowBalanceWallets() {
        walletRepository.findAll().stream()
                .filter(w -> w.getBalance().compareTo(w.getLowBalanceThreshold()) < 0)
                .forEach(w ->
                        eventProducer.publishWalletLowBalance(
                                w.toLowBalanceEvent()
                        )
                );
    }
}
