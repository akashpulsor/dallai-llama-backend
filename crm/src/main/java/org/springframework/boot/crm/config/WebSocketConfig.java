package org.springframework.boot.crm.config;

// ...existing imports...
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ApplicationEventPublisher applicationEventPublisher;

    public WebSocketConfig(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Use a handler factory to ensure a new handler instance per connection
        registry.addHandler(
            new WebSocketHandler() {
                @Override
                public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession session) throws Exception {
                    new MediaStreamHandler(applicationEventPublisher).afterConnectionEstablished(session);
                }
                @Override
                public void handleMessage(org.springframework.web.socket.WebSocketSession session, org.springframework.web.socket.WebSocketMessage<?> message) throws Exception {
                    new MediaStreamHandler(applicationEventPublisher).handleMessage(session, message);
                }
                @Override
                public void handleTransportError(org.springframework.web.socket.WebSocketSession session, Throwable exception) throws Exception {
                    new MediaStreamHandler(applicationEventPublisher).handleTransportError(session, exception);
                }
                @Override
                public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession session, org.springframework.web.socket.CloseStatus closeStatus) throws Exception {
                    new MediaStreamHandler(applicationEventPublisher).afterConnectionClosed(session, closeStatus);
                }
                @Override
                public boolean supportsPartialMessages() {
                    return false;
                }
            },
            "/api/call/media-stream"
        )
        .addInterceptors(new HttpSessionHandshakeInterceptor())
        .setAllowedOrigins("*");
    }
}
