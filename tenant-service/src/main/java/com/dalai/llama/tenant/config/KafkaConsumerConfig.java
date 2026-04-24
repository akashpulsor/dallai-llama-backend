package com.dalai.llama.tenant.config;

import com.dalai.llama.tenant.domain.event.BillingStateChangedEvent;
import com.dalai.llama.tenant.domain.event.SubscriptionActivatedEvent;
import com.dalai.llama.tenant.domain.event.SubscriptionActivationFailedEvent;
import com.dalai.llama.tenant.domain.event.WalletCreatedEvent;
import com.dalai.llama.tenant.domain.event.WalletCreditedEvent;
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

    @Value("${spring.kafka.consumer.group-id:tenant-service}")
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

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
        handler.addNotRetryableExceptions(DeserializationException.class, ClassCastException.class);
        return handler;
    }

    @Bean(name = "walletCreatedListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, WalletCreatedEvent>
    walletCreatedListenerFactory(DefaultErrorHandler kafkaErrorHandler) {
        return buildFactory(WalletCreatedEvent.class, kafkaErrorHandler);
    }


    @Bean(name = "walletCreditedListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, WalletCreditedEvent>
    walletCreditedListenerFactory(DefaultErrorHandler kafkaErrorHandler) {
        return buildFactory(WalletCreditedEvent.class, kafkaErrorHandler);
    }

    @Bean(name = "billingStateChangedListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, BillingStateChangedEvent>
    billingStateChangedListenerFactory(DefaultErrorHandler kafkaErrorHandler) {
        return buildFactory(BillingStateChangedEvent.class, kafkaErrorHandler);
    }

    @Bean(name = "subscriptionActivatedListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, SubscriptionActivatedEvent>
    subscriptionActivatedListenerFactory(DefaultErrorHandler kafkaErrorHandler) {
        return buildFactory(SubscriptionActivatedEvent.class, kafkaErrorHandler);
    }

    @Bean(name = "subscriptionActivationFailedListenerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, SubscriptionActivationFailedEvent>
    subscriptionActivationFailedListenerFactory(DefaultErrorHandler kafkaErrorHandler) {
        return buildFactory(SubscriptionActivationFailedEvent.class, kafkaErrorHandler);
    }
}