package com.dalai.llama.pbx.core.config;



import com.dalai.llama.pbx.core.websocket.TenantChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket for real-time events to Agent and Supervisor UIs.
 *
 * ════════════════════════════════════════════════════════════════
 * MULTI-POD ARCHITECTURE (why Redis Pub/Sub, not in-memory)
 * ════════════════════════════════════════════════════════════════
 *
 * Problem:
 *   PBX-Core runs N replicas behind a K8s Service. Agent on pod-1 subscribes
 *   to /topic/tenant/{tenantId}/calls. EslEventListener on pod-2 publishes
 *   CALL_ANSWERED. With simple in-memory broker, agent never sees the event.
 *
 * Solution:
 *   WebSocketEventPublisher.publish() → Redis PUBLISH "ws:{tenantId}:{channel}" {payload}
 *   Every pod subscribes to Redis channels matching its connected tenants.
 *   On Redis message → SimpMessagingTemplate.convertAndSend() to local WebSocket clients.
 *
 * Why Redis Pub/Sub and NOT Kafka:
 *   - Events are ephemeral (no durability/replay needed — offline agents miss events)
 *   - Sub-millisecond latency (Redis Pub/Sub) vs 5-50ms (Kafka consumer poll)
 *   - Redis is already in the stack (channel counters, config cache, active calls)
 *   - No consumer groups — every pod gets every message, delivers to its local clients
 *   - Kafka is for durable log streaming (CDR export, billing), not UI fan-out
 *
 * Why NOT RabbitMQ STOMP relay:
 *   - Adds another stateful service to manage
 *   - Redis is already there and sufficient for this scale (~1000 events/sec max)
 *
 * Implementation phases:
 *   Phase 1 (current): Simple in-memory broker — works for single replica / local dev.
 *     WebSocketEventPublisher → SimpMessagingTemplate directly.
 *
 *   Phase 2 (production multi-pod): Set pbxcore.websocket.broker=redis.
 *     WebSocketEventPublisher → Redis PUBLISH instead of SimpMessagingTemplate.
 *     RedisWebSocketBridge (bean in RedisConfig) subscribes to "ws:*" patterns
 *     and relays to local SimpMessagingTemplate.
 *     STOMP broker stays "simple" — Redis handles cross-pod, simple handles local delivery.
 *
 * ════════════════════════════════════════════════════════════════
 * STOMP TOPICS (tenant-scoped — enforced by TenantChannelInterceptor)
 * ════════════════════════════════════════════════════════════════
 *
 *   /topic/tenant/{tenantId}/calls      → CALL_RINGING, CALL_ANSWERED, CALL_ENDED, ...
 *   /topic/tenant/{tenantId}/agents     → AGENT_STATUS_CHANGED, AGENT_LOGGED_IN, ...
 *   /topic/tenant/{tenantId}/queues     → QUEUE_STATS_UPDATED, CALL_ENTERED_QUEUE, ...
 *   /topic/tenant/{tenantId}/campaigns  → CONTACT_DIALED, CONTACT_CONNECTED, ...
 *
 * Connection flow:
 *   Agent UI → ws://pbx-core:8081/ws → STOMP CONNECT with Authorization: Bearer {jwt}
 *   TenantChannelInterceptor validates JWT + enforces tenant isolation on SUBSCRIBE.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final TenantChannelInterceptor tenantChannelInterceptor;

    @Value("${pbxcore.websocket.allowed-origins:*}")
    private String[] allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory broker for /topic destinations.
        // Cross-pod delivery is handled by Redis Pub/Sub relay (Phase 2)
        // at the WebSocketEventPublisher level — NOT at the STOMP broker level.
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{10000, 10000})  // server→client, client→server: 10s
                .setTaskScheduler(brokerTaskScheduler());

        // Client-to-server prefix (e.g., agent sends status update via WS)
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Native WebSocket endpoint
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins);

        // SockJS fallback (older browsers, corporate proxies blocking WebSocket upgrade)
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins)
                .withSockJS()
                .setHeartbeatTime(25000);  // SockJS heartbeat 25s (keeps proxies alive)
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(64 * 1024);        // 64KB max per message
        registry.setSendBufferSizeLimit(512 * 1024);     // 512KB send buffer per session
        registry.setSendTimeLimit(20 * 1000);             // 20s send timeout
    }

    /**
     * Wire TenantChannelInterceptor into the inbound channel.
     * Intercepts CONNECT (JWT validation) and SUBSCRIBE (tenant isolation).
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(tenantChannelInterceptor);
    }

    /**
     * Dedicated task scheduler for STOMP heartbeats.
     * Without this, enableSimpleBroker heartbeat config silently fails.
     */
    private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler brokerTaskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}