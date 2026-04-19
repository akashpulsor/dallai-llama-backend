package com.dalai.llama.billing.kafka.consumer;

import com.dalai.llama.billing.service.CdrService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CdrConsumer {

    private final CdrService cdrService;

    @KafkaListener(topics = "call.billing", groupId = "billing-service")
    public void consume(Object event) {
        cdrService.processCompletedCdr(event);
    }
}
