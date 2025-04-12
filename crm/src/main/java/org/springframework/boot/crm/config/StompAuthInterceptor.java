package org.springframework.boot.crm.config;

import org.springframework.boot.crm.service.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;

@Slf4j
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    public StompAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            log.debug("Processing STOMP CONNECT command");
            String token = accessor.getFirstNativeHeader("Authorization");

            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
                if (jwtService.validateJwtToken(token)) {
                    String username = jwtService.getUserNameFromJwtToken(token);
                    log.debug("JWT token validated for user: {}", username);

                    Authentication auth = new UsernamePasswordAuthenticationToken(
                            username,
                            null,
                            new ArrayList<>()
                    );
                    accessor.setUser(auth);
                } else {
                    log.warn("Invalid JWT token in STOMP connection");
                }
            } else {
                log.warn("No valid Authorization header found in STOMP connection");
            }
        }
        return message;
    }
}