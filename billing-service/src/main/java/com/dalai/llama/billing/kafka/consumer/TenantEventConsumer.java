package com.dalai.llama.billing.kafka.consumer;

import com.dalai.llama.billing.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TenantEventConsumer {

    private final WalletService walletService;

    @KafkaListener(topics = "tenant.created", groupId = "billing-service")
    public void onTenantCreated(UUID tenantId) {
        walletService.createWallet(tenantId);
    }

    @KafkaListener(topics = "tenant.deleted", groupId = "billing-service")
    public void onTenantDeleted(UUID tenantId) {
        walletService.deleteWallet(tenantId);
    }
}
