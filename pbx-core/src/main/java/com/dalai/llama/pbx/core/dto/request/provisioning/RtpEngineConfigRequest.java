package com.dalai.llama.pbx.core.dto.request.provisioning;



import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Received from tenant-service RtpEngineConfigService.configureForSubscription().
 *
 * Tenant-service has already resolved:
 *   - codecs (from plan tier)
 *   - AI fork target (dedicated vs shared namespace)
 *   - recording path
 *
 * PBX-Core stores this in Redis. Kamailio reads per-tenant RTPEngine flags
 * at call time via /internal/kamailio/authorize which includes rtpengine hints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public class RtpEngineConfigRequest {

    private UUID tenantId;
    private UUID subscriptionId;
    private String namespace;
    private Boolean dedicatedInfrastructure;

    // Resolved by tenant-service
    private String codecs;                   // "PCMU,PCMA,G729,opus"
    private Boolean recordingEnabled;
    private String recordingPath;            // "/var/spool/rtpengine/{namespace}"
    private Integer maxChannels;
    private Boolean transcodingEnabled;

    // AI audio fork (for STT)
    private Boolean aiForkEnabled;
    private String aiForkTarget;             // "udp:ai-service.{ns}.svc:5555"
}