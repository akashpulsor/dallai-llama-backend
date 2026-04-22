package com.dalai.llama.billing.kafka.consumer;

import com.dalai.llama.billing.service.UsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DidEventConsumer {

    private final UsageService usageService;

    @KafkaListener(topics = "product.did.provisioned", groupId = "billing-service",
            containerFactory = "genericEventListenerFactory")
    public void onDidProvisioned(Object event) {
        log.info("Received product.did.provisioned: {}", event);
        usageService.trackProvisionedDid(event);
    }

    @KafkaListener(topics = "product.did.released", groupId = "billing-service",
            containerFactory = "genericEventListenerFactory")
    public void onDidReleased(Object event) {
        log.info("Received product.did.released: {}", event);
        usageService.untrackReleasedDid(event);
    }
}
