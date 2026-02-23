package com.dalai.llama.product.domain.entity.enums;

/**
 * App types for product apps.
 * These map to different UI applications.
 */
public enum AppType {
    CONTACT_CENTER,      // Main agent dashboard for AI_CC, BASIC_PBX
    IVR_BUILDER,         // IVR flow designer
    ADMIN_PANEL,         // Tenant admin panel
    SUPERVISOR,          // Supervisor dashboard
    WALLBOARD,           // Real-time wallboard
    REPORTING,           // Reports and analytics
    DIALER,              // Dialer dashboard for OUTBOUND_DIALER
    AGENT_DIALER,        // Agent-specific dialer UI
    RECEPTIONIST         // Virtual receptionist dashboard
}