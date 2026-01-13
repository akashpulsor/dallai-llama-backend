package com.dalai.llama.billing.kafka.consumer;

import com.dalai.llama.billing.service.CdrService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CdrConsumer {

    private final CdrService cdrService;

    @KafkaListener(topics = "cdr.completed", groupId = "billing-service")
    public void consume(Object event) {
        // event is deserialized CdrCompletedEvent (kept generic here)
        cdrService.processCompletedCdr(event);
    }
}
