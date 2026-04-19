package com.dalai.llama.billing.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic billingStateChangedTopic() {
        return new NewTopic("billing.state.changed", 3, (short) 1);
    }

    @Bean
    public NewTopic walletLowBalanceTopic() {
        return new NewTopic("billing.wallet.low", 3, (short) 1);
    }

    @Bean
    public NewTopic cdrRatedTopic() {
        return new NewTopic("billing.cdr.rated", 3, (short) 1);
    }

    @Bean
    public NewTopic subscriptionActivatedTopic() {
        return new NewTopic("billing.subscription.activated", 3, (short) 1);
    }
}
