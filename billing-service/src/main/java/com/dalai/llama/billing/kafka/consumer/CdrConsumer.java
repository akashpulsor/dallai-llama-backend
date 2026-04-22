package com.dalai.llama.billing.kafka.consumer;

import com.dalai.llama.billing.service.CdrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CdrConsumer {

    private final CdrService cdrService;

    @KafkaListener(topics = "call.billing", groupId = "billing-service",
            containerFactory = "genericEventListenerFactory")
    public void consume(Object event) {
        log.info("Received call.billing: {}", event);
        cdrService.processCompletedCdr(event);
    }
}
