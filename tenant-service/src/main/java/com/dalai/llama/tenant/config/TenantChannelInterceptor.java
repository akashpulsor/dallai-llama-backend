package com.dalai.llama.tenant.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class TenantChannelInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    private static final Pattern TENANT_DESTINATION_PATTERN =
            Pattern.compile("^/(topic|app)/tenant/([a-fA-F0-9\\-]+)/.*$");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            authenticate(accessor);
        }

        if (StompCommand.SUBSCRIBE.equals(command)
                || StompCommand.SEND.equals(command)) {
            authorize(accessor);
        }

        return message;
    }

    /**
     * AUTHENTICATION
     * Validate Keycloak JWT on STOMP CONNECT
     */
    private void authenticate(StompHeaderAccessor accessor) {
        String authHeader =
                accessor.getFirstNativeHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException(
                    "Missing or invalid Authorization header"
            );
        }

        String token = authHeader.substring(7);

        Jwt jwt = jwtDecoder.decode(token);

        String subject = jwt.getSubject();
        String tenantId = extractTenantId(jwt);

        List<SimpleGrantedAuthority> authorities =
                extractAuthorities(jwt);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        subject,
                        null,
                        authorities
                );

        accessor.setUser(authentication);

        Map<String, Object> sessionAttributes =
                accessor.getSessionAttributes();

        if (sessionAttributes != null) {
            sessionAttributes.put("tenantId", tenantId);
            sessionAttributes.put("subject", subject);
        }

        log.info(
                "WebSocket authenticated user={} tenant={}",
                subject,
                tenantId
        );
    }

    /**
     * AUTHORIZATION
     * Ensure user subscribes only to their tenant topics
     */
    private void authorize(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();

        if (destination == null) {
            return;
        }

        Matcher matcher =
                TENANT_DESTINATION_PATTERN.matcher(destination);

        if (!matcher.matches()) {
            return;
        }

        String requestedTenantId = matcher.group(2);

        String sessionTenantId =
                (String) accessor.getSessionAttributes()
                        .get("tenantId");

        if (!requestedTenantId.equals(sessionTenantId)) {
            log.warn(
                    "Unauthorized WS access requested={} session={}",
                    requestedTenantId,
                    sessionTenantId
            );

            throw new IllegalArgumentException(
                    "Unauthorized tenant access"
            );
        }

        log.debug(
                "Authorized tenant websocket access {}",
                destination
        );
    }

    /**
     * Extract tenant_id from Keycloak token
     */
    private String extractTenantId(Jwt jwt) {
        String tenantId = jwt.getClaimAsString("tenant_id");

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "tenant_id missing in token"
            );
        }

        return tenantId;
    }

    /**
     * Extract Keycloak roles
     */
    @SuppressWarnings("unchecked")
    private List<SimpleGrantedAuthority> extractAuthorities(Jwt jwt) {
        List<SimpleGrantedAuthority> roles = new ArrayList<>();

        Map<String, Object> realmAccess =
                jwt.getClaim("realm_access");

        if (realmAccess != null) {
            List<String> realmRoles =
                    (List<String>) realmAccess.get("roles");

            if (realmRoles != null) {
                realmRoles.forEach(role ->
                        roles.add(
                                new SimpleGrantedAuthority(
                                        "ROLE_" + role.toUpperCase()
                                )
                        )
                );
            }
        }

        return roles;
    }
}