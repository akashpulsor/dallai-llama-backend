package com.dalai.llama.creator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CreatorApiRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CreatorApiRequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean monitoredPath = path.startsWith("/api/v1/creator/shorts")
                || path.startsWith("/api/v1/creator/jobs")
                || (path.startsWith("/api/v1/creator/storyboards/scripts/")
                        && path.contains("/client-review"));
        if (!monitoredPath) {
            return true;
        }
        return isReadOnlyPollingRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String requestId = requestId(request);
        String authorization = request.getHeader("Authorization");
        boolean bearerPresent = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        log.info(
                "Creator API request started requestId={} method={} path={} query={} tenantId={} userHeaderPresent={} bearerPresent={} contentType={} contentLength={} remote={}",
                requestId,
                request.getMethod(),
                request.getRequestURI(),
                defaultString(request.getQueryString(), ""),
                defaultString(request.getHeader("X-Tenant-ID"), ""),
                hasText(request.getHeader("X-User-ID")),
                bearerPresent,
                defaultString(request.getContentType(), ""),
                request.getContentLengthLong(),
                request.getRemoteAddr()
        );
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info(
                    "Creator API request completed requestId={} method={} path={} status={} durationMs={}",
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs
            );
        }
    }

    private String requestId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-ID");
        return hasText(requestId) ? requestId : UUID.randomUUID().toString();
    }

    private boolean isReadOnlyPollingRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return path.equals("/api/v1/creator/shorts")
                || path.equals("/api/v1/creator/jobs")
                || path.matches("/api/v1/creator/shorts/[0-9a-fA-F-]{36}")
                || path.matches("/api/v1/creator/jobs/[0-9a-fA-F-]{36}");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String defaultString(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }
}
