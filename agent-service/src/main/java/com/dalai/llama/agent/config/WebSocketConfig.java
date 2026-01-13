package com.dalai.llama.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {

        // Simple broker backed by Redis (Spring automatically uses Redis for multi-node)
        config.enableSimpleBroker("/topic", "/queue");

        // All messages sent from UI must begin with /app/...
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {

        // Main WebSocket endpoint used by agents
        registry.addEndpoint("/ws/agent")
                .setAllowedOriginPatterns("*")         // allow all frontend origins
                .withSockJS();                         // enable SockJS fallback for corporate networks

        // Native WebSocket (optional, but recommended)
        registry.addEndpoint("/ws/agent")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {

        registration
                .setMessageSizeLimit(128 * 1024)       // messages up to 128 KB
                .setSendBufferSizeLimit(512 * 1024)    // send buffer
                .setSendTimeLimit(20000);              // 20 sec send timeout
    }

    // Required for heartbeats & scaling
    @Bean
    public ThreadPoolTaskScheduler messageBrokerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        return scheduler;
    }
}
