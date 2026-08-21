package com.dalai.llama.videogen.web;

/**
 * Request-scoped tenant identity, set once per request by {@link TenantContextFilter} and read
 * by any nested service that needs to call llm-gateway (which requires X-Tenant-ID on every
 * call). Avoids threading {@code tenantId} through every service interface's method signature --
 * same reasoning as Spring Security's {@code SecurityContextHolder}.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void set(TenantContext context) {
        CURRENT.set(context);
    }

    public static TenantContext get() {
        TenantContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("No TenantContext set for this request -- TenantContextFilter should have run first");
        }
        return context;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
