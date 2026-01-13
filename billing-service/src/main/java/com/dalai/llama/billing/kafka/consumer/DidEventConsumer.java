package com.dalai.llama.billing.kafka.consumer;

import com.dalai.llama.billing.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DidEventConsumer {

    private final UsageService usageService;

    @KafkaListener(topics = "product.did.provisioned", groupId = "billing-service")
    public void onDidProvisioned(Object event) {
        // Stored for monthly rental job
        usageService.trackProvisionedDid(event);
    }

    @KafkaListener(topics = "product.did.released", groupId = "billing-service")
    public void onDidReleased(Object event) {
        usageService.untrackReleasedDid(event);
    }
}
