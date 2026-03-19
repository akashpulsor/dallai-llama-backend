package com.dalai.llama.pbx.core.dto.request.provisioning;



import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Received from tenant-service CoTurnConfigService.configureForSubscription().
 *
 * PBX-Core owns the CoTURN shared secret. It generates HMAC-SHA1 credentials,
 * stores in Redis + DB, and returns URLs + creds to tenant-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnConfigRequest {

    private UUID tenantId;
    private UUID subscriptionId;
    private String namespace;
    private String planTier;
    private Boolean dedicatedInfrastructure;
}