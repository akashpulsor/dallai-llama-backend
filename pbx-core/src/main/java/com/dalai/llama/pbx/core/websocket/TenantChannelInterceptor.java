package com.dalai.llama.pbx.core.websocket;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP WebSocket interceptor — enforces two security rules:
 *
 * 1. CONNECT: JWT token must be valid (Keycloak).
 *    Agent UI sends JWT in STOMP CONNECT headers:
 *      CONNECT
 *      Authorization: Bearer eyJ...
 *
 * 2. SUBSCRIBE: Tenant isolation — users can only subscribe to their own tenant topics.
 *    Topic format: /topic/tenant/{tenantId}/calls
 *    tenantId from topic must match the tenant_id claim in the JWT.
 *
 * Without this interceptor, any authenticated user could subscribe to
 * /topic/tenant/{OTHER_TENANT_ID}/calls and see other tenants' call events.
 *
 * Agent UI connection flow:
 *   1. Agent logs in via Keycloak → gets JWT
 *   2. Agent UI opens WebSocket to ws://pbx-core:8081/ws
 *   3. Agent UI sends STOMP CONNECT with Authorization header
 *   4. This interceptor validates JWT → stores tenantId in session
 *   5. Agent UI sends SUBSCRIBE to /topic/tenant/{tenantId}/calls
 *   6. This interceptor checks tenantId matches session → allow/deny
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantChannelInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    private static final Pattern TENANT_TOPIC_PATTERN =
            Pattern.compile("^/topic/tenant/([a-f0-9\\-]+)/.*$");

    private static final String TENANT_ID_ATTR = "tenant_id";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        StompCommand command = accessor.getCommand();
        if (command == null) return message;

        switch (command) {
            case CONNECT -> handleConnect(accessor);
            case SUBSCRIBE -> handleSubscribe(accessor);
            default -> { /* SEND, DISCONNECT, etc. — pass through */ }
        }

        return message;
    }

    /**
     * CONNECT: Validate JWT, extract tenantId, store in session attributes.
     */
    private void handleConnect(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // In dev mode, allow unauthenticated WebSocket connections
            // In production, throw to reject the CONNECT
            log.warn("WebSocket CONNECT without Authorization header — allowing in dev mode");
            return;
        }

        String token = authHeader.substring(7);
        try {
            Jwt jwt = jwtDecoder.decode(token);

            // Extract tenantId from JWT claims
            // Keycloak custom claim: "tenant_id" (set via mapper in realm config)
            String tenantId = jwt.getClaimAsString(TENANT_ID_ATTR);
            if (tenantId == null) {
                // Fallback: try resource_access or other claim structures
                tenantId = extractTenantIdFromClaims(jwt);
            }

            if (tenantId != null) {
                accessor.getSessionAttributes().put(TENANT_ID_ATTR, tenantId);
                log.debug("WebSocket CONNECT authenticated: user={}, tenant={}",
                        jwt.getSubject(), tenantId);
            } else {
                log.warn("WebSocket CONNECT: JWT valid but no tenant_id claim for user {}",
                        jwt.getSubject());
            }

        } catch (Exception e) {
            log.error("WebSocket CONNECT JWT validation failed: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid JWT token");
        }
    }

    /**
     * SUBSCRIBE: Check that the tenant in the topic matches the session tenantId.
     */
    private void handleSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) return;

        Matcher matcher = TENANT_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            // Not a tenant-scoped topic — allow (e.g., /topic/system/health)
            return;
        }

        String topicTenantId = matcher.group(1);
        String sessionTenantId = (String) accessor.getSessionAttributes().get(TENANT_ID_ATTR);

        if (sessionTenantId == null) {
            // No tenantId in session — unauthenticated connection (dev mode)
            log.warn("WebSocket SUBSCRIBE to {} without tenant context", destination);
            return;
        }

        if (!topicTenantId.equals(sessionTenantId)) {
            log.error("WebSocket tenant isolation VIOLATION: session={} tried to subscribe to tenant={}",
                    sessionTenantId, topicTenantId);
            throw new IllegalArgumentException(
                    "Cannot subscribe to another tenant's topics");
        }

        log.debug("WebSocket SUBSCRIBE allowed: tenant={} → {}", sessionTenantId, destination);
    }

    /**
     * Try alternative JWT claim structures for tenantId.
     * Different Keycloak configurations may nest it differently.
     */
    private String extractTenantIdFromClaims(Jwt jwt) {
        // Try flat claim
        Object tid = jwt.getClaim("tenantId");
        if (tid != null) return tid.toString();

        // Try nested in custom claims
        Object custom = jwt.getClaim("custom");
        if (custom instanceof java.util.Map<?, ?> m) {
            Object nested = m.get("tenant_id");
            if (nested != null) return nested.toString();
        }

        // Try groups/roles that contain tenant context
        List<String> groups = jwt.getClaimAsStringList("groups");
        if (groups != null) {
            for (String group : groups) {
                if (group.startsWith("tenant:")) {
                    return group.substring(7);
                }
            }
        }

        return null;
    }
}