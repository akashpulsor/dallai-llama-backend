package com.dalai.llama.tenant.kafka.consumer;

import com.dalai.llama.tenant.domain.event.BillingStateChangedEvent;
import com.dalai.llama.tenant.domain.event.WalletCreatedEvent;
import com.dalai.llama.tenant.domain.event.WalletCreditedEvent;
import com.dalai.llama.tenant.domain.event.WalletExternalEvent;
import com.dalai.llama.tenant.domain.exception.TenantNotFoundException;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.service.impl.TenantWebSocketPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEventConsumer {

    private final TenantService tenantService;


    @KafkaListener(topics = "billing.wallet.created", groupId = "billing-service",
            containerFactory = "walletCreatedListenerFactory")
    @RetryableTopic(
            attempts = "5",
            backoff = @Backoff(delay = 500, multiplier = 2.0, maxDelay = 10000),
            include = { TenantNotFoundException.class },
            dltStrategy = DltStrategy.FAIL_ON_ERROR
    )
    public void onWalletCreated(WalletCreatedEvent event) {
        log.info("Received billing.wallet.created: tenantId={} walletId={}",
                event.getTenantId(), event.getWalletId());
        tenantService.onWalletCreated(event.getTenantId(), event.getWalletId());
    }


    @KafkaListener(topics = "billing.wallet.funded", groupId = "billing-service",
            containerFactory = "walletCreditedListenerFactory")
    public void onWalletCredited(WalletCreditedEvent event) {
        log.info("Received billing.wallet.credited: tenantId={} amount={} balanceAfter={} subscriptionId={}",
                event.getTenantId(), event.getAmount(), event.getTotalBalance(), event.getSubscriptionId());

        tenantService.onWalletFunded(event);
        // Push balance update to UI via WebSocket

    }

    @KafkaListener(topics = "billing.state.changed", groupId = "billing-service",
            containerFactory = "billingStateChangedListenerFactory")
    public void onBillingStateChanged(BillingStateChangedEvent event) {
        log.info("Received billing.state.changed: tenantId={} state={}",
                event.getTenantId(), event.getCurrentState());
        tenantService.onBillingStateChanged(event.getTenantId(), event.getCurrentState().toString());
    }


}