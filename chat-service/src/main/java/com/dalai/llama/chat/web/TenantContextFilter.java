package com.dalai.llama.chat.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Same X-Tenant-ID + JWT-subject convention as every other service in this system. */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);
    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            UUID tenantId = parseUuidOrNull(request.getHeader(TENANT_HEADER));
            UUID userId = resolveUserId();
            // Diagnostic: capture what actually arrives on chat-session requests, to root-cause a
            // reported 403 (whether the tenant header / JWT reach chat-service at all).
            if (request.getRequestURI() != null && request.getRequestURI().contains("/v1/chat-sessions")) {
                log.info("CHAT_REQ_DIAG method={} uri={} tenantHeaderPresent={} tenantParsed={} jwtSubjectPresent={} authHeaderPresent={}",
                        request.getMethod(), request.getRequestURI(),
                        request.getHeader(TENANT_HEADER) != null, tenantId != null,
                        userId != null, request.getHeader("Authorization") != null);
            }
            if (tenantId != null) {
                TenantContextHolder.set(new TenantContext(tenantId, userId));
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private UUID resolveUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return parseUuidOrNull(jwt.getSubject());
        }
        return null;
    }

    private UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
