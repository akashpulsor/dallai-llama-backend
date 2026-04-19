package com.dalai.llama.tenant.domain.entity.enums;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProvisioningStep {

    // ── Identity & Auth ──
    FETCH_ENTITLEMENTS(1, "Fetch plan entitlements from product-service", false),
    RESOLVE_NAMESPACE(2, "Determine namespace (shared vs dedicated)", false),
    CREATE_KEYCLOAK_CLIENT(3, "Create Keycloak OIDC client in tenant realm", true),
    CREATE_ADMIN_USER(4, "Create tenant admin user in Keycloak", true),

    // ── Telecom Stack ──
    CONFIGURE_KAMAILIO(5, "Configure Kamailio via PBX-Core", true),
    CONFIGURE_FREESWITCH(6, "Generate & store FreeSWITCH dialplan", true),
    CONFIGURE_COTURN(7, "Configure CoTURN credentials", true),
    CONFIGURE_RTPENGINE(8, "Configure RTPEngine flags & AI fork", true),
    CONFIGURE_AI_SERVICE(9, "Configure voice-brain AI pipeline", true),

    // ── Infrastructure ──
    STAMP_INFRA_URLS(10, "Stamp infra URLs & feature flags into TenantApp", false),
    CONFIGURE_ISTIO_ROUTES(11, "Create Istio VirtualService for tenant hosts", true),
    CONFIGURE_MINIO_BUCKETS(12, "Ensure MinIO buckets for recordings/voicemail", true),

    // ── Finalize ──
    SYNC_PBX_CORE(13, "Sync full config to PBX-Core", true),
    HEALTH_CHECK(14, "Verify all endpoints reachable", false),
    FINALIZE(15, "Mark tenant ACTIVE", false);

    private final int order;
    private final String description;
    private final boolean compensatable;

    /** Next step in sequence, null if last */
    public ProvisioningStep next() {
        ProvisioningStep[] all = values();
        for (int i = 0; i < all.length - 1; i++) {
            if (all[i] == this) return all[i + 1];
        }
        return null;
    }

    /** Get step by order number */
    public static ProvisioningStep fromOrder(int order) {
        for (ProvisioningStep s : values()) {
            if (s.order == order) return s;
        }
        throw new IllegalArgumentException("No step with order: " + order);
    }
}