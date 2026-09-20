package com.dalai.llama.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Populates the SLF4J MDC with request-scoped context so every log line carries tenant_id,
 * project_id, shot_id, job_id, user_id, and request_id as top-level JSON fields (Loki filters
 * on those without regex-parsing the message).
 *
 * <p>Lookup order for tenant/user, first non-empty wins:
 * <ol>
 *   <li>Standard {@code X-Tenant-Id} / {@code X-User-Id} headers set by service-to-service
 *       callers -- llm-gateway's internal chain does this today.</li>
 *   <li>JWT claim {@code tenant_id} / {@code sub}, when Spring Security put a {@link Jwt} on the
 *       {@link SecurityContextHolder}.</li>
 * </ol>
 *
 * <p>Path variables for {@code projectId}, {@code shotId}, {@code jobId} land in MDC directly from
 * Spring MVC's {@link HandlerMapping#URI_TEMPLATE_VARIABLES_ATTRIBUTE} -- populated by the time
 * this filter runs post-handler-mapping. Request id comes from {@code X-Request-Id} if the caller
 * sent one, otherwise a fresh UUID.
 *
 * <p>Ordered before the security filters ({@link Ordered#HIGHEST_PRECEDENCE} + a small offset) so
 * a log line emitted by the security stack itself already carries the tenant context. Cleared in
 * a {@code finally} on every exit path -- an uncaught MDC key leak into a thread pool would mis-
 * attribute the next request's logs.
 */
public class TenantMdcFilter extends OncePerRequestFilter implements Ordered {

    private static final String HDR_TENANT = "X-Tenant-Id";
    private static final String HDR_USER = "X-User-Id";
    private static final String HDR_REQUEST_ID = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            putIfPresent(MdcKeys.REQUEST_ID, headerOrRandomRequestId(request));
            putIfPresent(MdcKeys.TENANT_ID, resolveTenantId(request));
            putIfPresent(MdcKeys.USER_ID, resolveUserId(request));
            putIfPresent(MdcKeys.PROJECT_ID, pathVar(request, "projectId"));
            putIfPresent(MdcKeys.SHOT_ID, pathVar(request, "shotId"));
            putIfPresent(MdcKeys.JOB_ID, pathVar(request, "jobId"));
            chain.doFilter(request, response);
        } finally {
            // Clear per-request MDC. Leaving keys on the thread lets them bleed into the next
            // request the same pool thread handles, which is exactly the kind of bug that would
            // make our per-tenant filtering worse than no filter at all.
            MDC.remove(MdcKeys.REQUEST_ID);
            MDC.remove(MdcKeys.TENANT_ID);
            MDC.remove(MdcKeys.USER_ID);
            MDC.remove(MdcKeys.PROJECT_ID);
            MDC.remove(MdcKeys.SHOT_ID);
            MDC.remove(MdcKeys.JOB_ID);
        }
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private static String headerOrRandomRequestId(HttpServletRequest request) {
        String header = request.getHeader(HDR_REQUEST_ID);
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }

    private static String resolveTenantId(HttpServletRequest request) {
        String fromHeader = request.getHeader(HDR_TENANT);
        if (fromHeader != null && !fromHeader.isBlank()) return fromHeader;
        Jwt jwt = jwtOrNull();
        if (jwt == null) return null;
        Object claim = jwt.getClaim(MdcKeys.TENANT_ID);
        return claim == null ? null : claim.toString();
    }

    private static String resolveUserId(HttpServletRequest request) {
        String fromHeader = request.getHeader(HDR_USER);
        if (fromHeader != null && !fromHeader.isBlank()) return fromHeader;
        Jwt jwt = jwtOrNull();
        if (jwt == null) return null;
        return jwt.getSubject();
    }

    /** {@link SecurityContextHolder} + {@link Jwt} live in spring-security, which is an optional
     * dep. Wrap the lookup so a service without security on the classpath still compiles and
     * runs (the JWT branch simply returns null and header/path fallbacks handle it). */
    private static Jwt jwtOrNull() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) return null;
            Object principal = auth.getPrincipal();
            return principal instanceof Jwt jwt ? jwt : null;
        } catch (NoClassDefFoundError | Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String pathVar(HttpServletRequest request, String name) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map<?, ?> map)) return null;
        Object value = ((Map<String, Object>) map).get(name);
        return value == null ? null : value.toString();
    }

    @Override
    public int getOrder() {
        // Before the security filter chain (which uses Ordered.HIGHEST_PRECEDENCE + 100 by
        // default) so security-emitted log lines already have tenant context.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
