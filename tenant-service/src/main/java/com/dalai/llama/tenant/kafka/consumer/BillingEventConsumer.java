package com.dalai.llama.tenant.kafka.consumer;


import com.dalai.llama.tenant.service.TenantService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEventConsumer {

    private final TenantService tenantService;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @PostConstruct
    public void start() {
        new Thread(this::pollLoop, "billing-event-consumer").start();
    }

    private void pollLoop() {
        KafkaConsumer<String, Map<String, Object>> consumer =
                new KafkaConsumer<>(Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                        ConsumerConfig.GROUP_ID_CONFIG, "tenant-billing-consumer",
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        org.springframework.kafka.support.serializer.JsonDeserializer.class,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
                ));

        consumer.subscribe(List.of(
                "billing.wallet.created",
                "billing.wallet.funded",
                "billing.state.changed"
        ));

        while (true) {
            for (ConsumerRecord<String, Map<String, Object>> record :
                    consumer.poll(Duration.ofSeconds(1))) {

                Map<String, Object> payload = record.value();
                UUID tenantId = UUID.fromString(payload.get("tenantId").toString());

                switch (record.topic()) {
                    case "billing.wallet.created" ->
                            tenantService.onWalletCreated(
                                    tenantId,
                                    UUID.fromString(payload.get("walletId").toString())
                            );
                    case "billing.wallet.funded" ->
                            tenantService.onWalletFunded(tenantId);
                    case "billing.state.changed" ->
                            tenantService.onBillingStateChanged(
                                    tenantId,
                                    payload.get("state").toString()
                            );
                }
            }
        }
    }
}
