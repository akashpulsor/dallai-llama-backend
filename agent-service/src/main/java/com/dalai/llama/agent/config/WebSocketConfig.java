package com.dalai.llama.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${spring.kafka.bootstrap-servers}")
    private String kafkaBrokers;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Use Kafka as message broker instead of simple in-memory broker
        config.enableStompBrokerRelay("/topic", "/queue")
                .setRelayHost(kafkaBrokers) // Update with actual Kafka broker
                .setRelayPort(61613) // STOMP port
                .setSystemLogin("guest")
                .setSystemPasscode("guest");

        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/agent")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(128 * 1024)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(20000);
    }
}
