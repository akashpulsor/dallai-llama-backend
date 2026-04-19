package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.request.RtpEngineConfigRequest;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import com.dalai.llama.tenant.service.client.PbxCoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * RTPEngine Configuration Service
 *
 * Audio architecture (single STT, no duplication):
 *
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │ BOT CALLS (CONV_IVR, VIRTUAL_RECEPTIONIST, OUTBOUND_DIALER)   │
 *   │                                                                 │
 *   │ Caller ↔ RTPEngine ↔ FreeSWITCH                                │
 *   │                         └─ mod_audio_stream WS ─→ voice-brain  │
 *   │                                                      │         │
 *   │                                    STT (single decode)         │
 *   │                                      ├─→ LLM → TTS → FS (bot) │
 *   │                                      ├─→ POST /transcript/live │
 *   │                                      └─→ POST /sentiment       │
 *   │                                                                 │
 *   │ RTPEngine fork: OFF (WS already has the audio)                 │
 *   └─────────────────────────────────────────────────────────────────┘
 *
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │ AGENT CALLS (AI_CC agent legs, BASIC_PBX)                      │
 *   │                                                                 │
 *   │ Caller ↔ RTPEngine ↔ FreeSWITCH ↔ Agent (human, no bot)       │
 *   │              └─ RTP fork ──→ voice-brain:5555 (UDP)            │
 *   │                                    │                            │
 *   │                              STT (passive tap)                  │
 *   │                                ├─→ POST /transcript/live       │
 *   │                                ├─→ POST /sentiment             │
 *   │                                └─→ agent assist suggestions    │
 *   │                                                                 │
 *   │ RTPEngine fork: ON (only audio tap for agent calls)            │
 *   └─────────────────────────────────────────────────────────────────┘
 *
 *   AI_CC is hybrid: bot handles initial IVR (WS path, no fork),
 *   on escalation to agent PBX-Core toggles fork ON dynamically
 *   via RTPEngine control protocol. We provision fork=ON so ready.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RtpEngineConfigService {

    private final PbxCoreClient pbxCoreClient;

    @Value("${dalaillama.minio.bucket-prefix:dl}")
    private String minioBucketPrefix;

    /**
     * Internal host — used when RTPEngine is INSIDE the cluster (dedicated infra).
     * Default: K8s service DNS.
     */
    @Value("${dalaillama.ai-service.rtp-host:ai-service.apps.svc.cluster.local}")
    private String voiceBrainRtpHost;

    @Value("${dalaillama.ai-service.rtp-port:5555}")
    private int voiceBrainRtpPort;

    /**
     * External host — used when RTPEngine is OUTSIDE the cluster (shared infra).
     * Must be set to the K8s node IP where ai-service pod runs (hostPort binding).
     * hostPort 5555 maps directly — no kube-proxy or Istio in the path.
     */
    @Value("${dalaillama.ai-service.rtp-external-host:${dalaillama.ai-service.rtp-host:ai-service.apps.svc.cluster.local}}")
    private String voiceBrainRtpExternalHost;

    @Value("${dalaillama.ai-service.rtp-external-port:5555}")
    private int voiceBrainRtpExternalPort;

    /** Products where ALL calls go through bot WS — no RTP fork needed */
    private static final Set<String> BOT_ONLY_PRODUCTS = Set.of(
            "CONV_IVR", "VIRTUAL_RECEPTIONIST", "OUTBOUND_DIALER"
    );

    /** Products with agent legs needing RTP fork for live transcription */
    private static final Set<String> AGENT_PRODUCTS = Set.of(
            "AI_CC", "BASIC_PBX"
    );

    public void configureForSubscription(TenantApp app, PlanEntitlementResponse e) {
        log.info("Configuring RTPEngine for subscription {}", app.getSubscriptionId());

        boolean dedicated = Boolean.TRUE.equals(app.getDedicatedInfrastructure());
        String codecs = resolveCodecs(app.getPlanTier());
        String slug = app.getTenant().getSlug();
        String ns = app.getNamespace();
        String productCode = app.getProductCode();

        String recordingPath = minioBucketPrefix + "-" + slug + "-call-recordings";

        // Fork only for products with human agent calls
        // Bot products reuse STT from mod_audio_stream WebSocket (zero extra cost)
        boolean needsFork = e.aiSttEnabled() && AGENT_PRODUCTS.contains(productCode);

        String aiForkTarget = null;
        if (needsFork) {
            if (dedicated) {
                // Dedicated infra: RTPEngine is inside the same namespace → use internal DNS
                aiForkTarget = "udp:voice-brain." + ns + ".svc.cluster.local:" + voiceBrainRtpPort;
            } else {
                // Shared infra: RTPEngine is OUTSIDE the cluster → use external host + hostPort
                aiForkTarget = "udp:" + voiceBrainRtpExternalHost + ":" + voiceBrainRtpExternalPort;
            }
        }

        RtpEngineConfigRequest request = RtpEngineConfigRequest.builder()
                .tenantId(app.getTenant().getId())
                .subscriptionId(app.getSubscriptionId())
                .namespace(ns)
                .dedicatedInfrastructure(dedicated)
                .codecs(codecs)
                .recordingEnabled(e.recordingEnabled())
                .recordingPath(recordingPath)
                .maxChannels(e.maxPstnChannels())
                .transcodingEnabled(true)
                .aiForkEnabled(needsFork)
                .aiForkTarget(aiForkTarget)
                .build();

        pbxCoreClient.configureRtpEngine(request);

        log.info("RTPEngine [{}] product={} codecs={} recording={} fork={} target={}",
                slug, productCode, codecs, e.recordingEnabled(), needsFork, aiForkTarget);
    }

    private String resolveCodecs(String planTier) {
        return switch (planTier) {
            case "ENTERPRISE" -> "opus,G722,PCMU,PCMA";
            case "PROFESSIONAL" -> "opus,PCMU,PCMA";
            default -> "PCMU,PCMA";
        };
    }

    public void removeSubscriptionConfig(String tenantId) {
        pbxCoreClient.removeRtpEngineConfig(tenantId);
    }
}