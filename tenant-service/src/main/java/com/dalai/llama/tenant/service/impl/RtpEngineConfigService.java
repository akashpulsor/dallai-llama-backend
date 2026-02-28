package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.request.RtpEngineConfigRequest;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import com.dalai.llama.tenant.service.client.PbxCoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RTPEngine Configuration Service (Tenant Service side)
 *
 * Responsibility split:
 *   Tenant Service (here):
 *     - Plan tier → codec preference (business logic)
 *     - Entitlement → recording/AI fork flags
 *     - Recording path resolution
 *
 *   PBX-Core (remote):
 *     - Redis storage (rtpengine:tenant:{id})
 *     - Runtime flag generation: GET /internal/rtpengine/flags/{tenantId}
 *       (Kamailio calls this before rtpengine_manage() to get per-tenant flags)
 *     - Knows nothing about plan tiers or entitlements
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RtpEngineConfigService {

    private final PbxCoreClient pbxCoreClient;

    public void configureForSubscription(TenantApp app, PlanEntitlementResponse e) {
        log.info("Configuring RTPEngine via PBX-Core for subscription {}", app.getSubscriptionId());

        boolean dedicated = Boolean.TRUE.equals(app.getDedicatedInfrastructure());

        // ── Business logic stays here: tier → codecs ──
        String codecs = resolveCodecs(app.getPlanTier());

        // ── Business logic: AI fork target resolution ──
        String aiForkTarget = null;
        if (e.aiSttEnabled()) {
            aiForkTarget = dedicated
                    ? "udp:ai-service." + app.getNamespace() + ".svc.cluster.local:5555"
                    : "udp:ai-service.telecom.svc.cluster.local:5555";
        }

        RtpEngineConfigRequest request = RtpEngineConfigRequest.builder()
                .tenantId(app.getTenant().getId())
                .subscriptionId(app.getSubscriptionId())
                .namespace(app.getNamespace())
                .dedicatedInfrastructure(dedicated)
                // Resolved values
                .codecs(codecs)
                .recordingEnabled(e.recordingEnabled())
                .recordingPath("/var/spool/rtpengine/" + app.getNamespace())
                .maxChannels(e.maxPstnChannels())
                .transcodingEnabled(true)
                // AI audio fork
                .aiForkEnabled(e.aiSttEnabled())
                .aiForkTarget(aiForkTarget)
                .build();

        pbxCoreClient.configureRtpEngine(request);

        log.info("RTPEngine configured via PBX-Core for {} - codecs: {}, recording: {}, aiFork: {}",
                app.getNamespace(), codecs, e.recordingEnabled(), e.aiSttEnabled());
    }

    /**
     * Plan tier → codec preference. Business logic stays in tenant-service.
     * PBX-Core stores "opus,G722,PCMU,PCMA" as a string, doesn't interpret tiers.
     */
    private String resolveCodecs(String planTier) {
        return switch (planTier) {
            case "ENTERPRISE" -> "opus,G722,PCMU,PCMA";
            case "PROFESSIONAL" -> "opus,PCMU,PCMA";
            default -> "PCMU,PCMA";
        };
    }

    public void removeSubscriptionConfig(String tenantId) {
        log.info("Removing RTPEngine config via PBX-Core for tenant {}", tenantId);
        pbxCoreClient.removeRtpEngineConfig(tenantId);
    }
}