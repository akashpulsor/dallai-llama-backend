package com.dalai.llama.billing.kafka.consumer;


import com.dalai.llama.billing.domain.event.TenantActivatedEvent;
import com.dalai.llama.billing.domain.event.TenantCreatedEvent;
import com.dalai.llama.billing.domain.event.TenantDeletedEvent;
import com.dalai.llama.billing.domain.event.TenantStateChangedEvent;
import com.dalai.llama.billing.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantEventConsumer {

    private final WalletService walletService;

    @KafkaListener(topics = "tenant.created", groupId = "billing-service",
            containerFactory = "tenantCreatedListenerFactory")
    public void onTenantCreated(TenantCreatedEvent event) {
        log.info("Received tenant.created: tenantId={} slug={}", event.tenantId(), event.slug());
        walletService.createWallet(event.tenantId());
    }

    @KafkaListener(topics = "tenant.deleted", groupId = "billing-service",
            containerFactory = "tenantDeletedListenerFactory")
    public void onTenantDeleted(TenantDeletedEvent event) {
        log.info("Received tenant.deleted: tenantId={}", event.tenantId());
        walletService.deleteWallet(event.tenantId());
    }

    @KafkaListener(topics = "tenant.activated", groupId = "billing-service",
            containerFactory = "tenantActivatedListenerFactory")
    public void onTenantActivated(TenantActivatedEvent event) {
        log.info("Received tenant.activated: tenantId={}", event.tenantId());
        // TODO: post-MVP — reactivate wallet, clear suspension flags, resume billing cycle
    }

    @KafkaListener(topics = "tenant.state.changed", groupId = "billing-service",
            containerFactory = "tenantStateChangedListenerFactory")
    public void onTenantStateChanged(TenantStateChangedEvent event) {
        log.info("Received tenant.state.changed: tenantId={} {} -> {} ({})",
                event.tenantId(), event.oldState(), event.newState(), event.message());
        // TODO: post-MVP — handle SUSPENDED → mark wallet frozen; ACTIVE → unfreeze
    }
}