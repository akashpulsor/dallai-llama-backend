package com.dalai.llama.pbx.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

/**
 * TenantProvisioningRequest:
 * Minimal input from UI.
 * Everything else is generated dynamically inside ProvisioningService.
 */
@Data
public class TenantProvisioningRequest {

    /**
     * Example: "acme.dalaillama.io"
     * This defines per-tenant DNS root.
     */
    @NotBlank
    private String authRealm;

    /**
     * Optional. If omitted, ProvisioningService auto-generates:
     *  - sip.<client>.<realmname>
     *  - pbx.<client>.<realmname>
     *  - turn.<client>.<realmname>
     *  - wss.<client>.<realmname>
     */
    private String clientName;

    /**
     * AI Feature Flags as enabled by the tenant.
     */
    private AIFeatures aiFeatures;

    @Data
    public static class AIFeatures {
        private boolean routing;
        private boolean transcription;
        private boolean noiseCancellation;
    }
}
