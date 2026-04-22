package com.dalai.llama.billing.config;

import com.dalai.llama.billing.domain.event.TenantActivatedEvent;
import com.dalai.llama.billing.domain.event.TenantCreatedEvent;
import com.dalai.llama.billing.domain.event.TenantDeletedEvent;
import com.dalai.llama.billing.domain.event.TenantStateChangedEvent;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:billing-service}")
    private String groupId;

    private Map<String, Object> baseProps() {
        Map<String, Object> p = new HashMap<>();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        p.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        p.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        p.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        p.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return p;
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> buildFactory(
            Class<T> valueType, DefaultErrorHandler errorHandler) {
        Map<String, Object> props = baseProps();
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, valueType.getName());

        DefaultKafkaConsumerFactory<String, T> cf = new DefaultKafkaConsumerFactory<>(props);
        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    // Generic factory used by consumers that don't have typed events yet
    // (product.did.* and call.billing have no producers today)
    private ConcurrentKafkaListenerContainerFactory<String, Object> buildGenericFactory(
            DefaultErrorHandler errorHandler) {
        Map<String, Object> props = baseProps();
        // No VALUE_DEFAULT_TYPE → Jackson will produce LinkedHashMap for generic JSON
        DefaultKafkaConsumerFactory<String, Object> cf = new DefaultKafkaConsumerFactory<>(props);
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
        handler.addNotRetryableExceptions(DeserializationException.class, ClassCastException.class);
        return handler;
    }

    @Bean(name = "tenantCreatedListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, TenantCreatedEvent>
    tenantCreatedListenerFactory(DefaultErrorHandler kafkaErrorHandler) {
        return buildFactory(TenantCreatedEvent.class, kafkaErrorHandler);
    }

    @Bean(name = "tenantDeletedListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, TenantDeletedEvent>
    tenantDeletedListenerFactory(DefaultErrorHandler kafkaErrorHandler) {
        return buildFactory(TenantDeletedEvent.class, kafkaErrorHandler);
    }

    @Bean(name = "tenantStateChangedListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, TenantStateChangedEvent>
    tenantStateChangedListenerFactory(DefaultErrorHandler kafkaErrorHandler) {
        return buildFactory(TenantStateChangedEvent.class, kafkaErrorHandler);
    }

    @Bean(name = "tenantActivatedListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, TenantActivatedEvent>
    tenantActivatedListenerFactory(DefaultErrorHandler kafkaErrorHandler) {
        return buildFactory(TenantActivatedEvent.class, kafkaErrorHandler);
    }

    @Bean(name = "genericEventListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object>
    genericEventListenerFactory(DefaultErrorHandler kafkaErrorHandler) {
        return buildGenericFactory(kafkaErrorHandler);
    }
}