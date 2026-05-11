package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.request.FreeSwitchDialplanRequest;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import com.dalai.llama.tenant.service.client.PbxCoreClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * FreeSWITCH Dialplan Configuration — generates XML dialplan per tenant.
 *
 * Identity model:
 *   - FreeSWITCH context = "tenant_{uuid_hex}" (collision-proof, see contextFor)
 *   - This must match Subscriber.namespace set by AgentService.contextFor()
 *   - Slug/namespace remain only as display labels in comments and CDR
 *
 * Flow:
 *   Provisioning: tenant-service → generates XML → POST to PBX-Core → stored in tenant_dialplan
 *   Runtime: FreeSWITCH → mod_xml_curl GET /internal/freeswitch/dialplan → PBX-Core returns stored XML
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FreeSwitchConfigService {

    private final PbxCoreClient pbxCoreClient;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${dalaillama.ai-service.ws-url:ws://127.0.0.1:8601}")
    private String voiceBrainUrl;

    /**
     * Build FreeSWITCH context name from tenant UUID.
     * Format: "tenant_{uuid_hex}" — 32 hex chars, no dashes.
     *
     * Collision-proof. Must match AgentService.contextFor() in pbx-core.
     */
    public static String contextFor(UUID tenantId) {
        return "tenant_" + tenantId.toString().replace("-", "");
    }

    public String configureForSubscription(TenantApp app, PlanEntitlementResponse e) {
        log.info("Generating FreeSWITCH XML dialplan: tenant={} product={}",
                app.getNamespace(), app.getProductCode());

        String ctx = contextFor(app.getTenant().getId());
        String tenantId = app.getTenant().getId().toString();

        String xml = switch (app.getProductCode()) {
            case "AI_CC" -> generateAiContactCenter(ctx, tenantId, app, e);
            case "CONV_IVR" -> generateConversationalIvr(ctx, tenantId, app, e);
            case "BASIC_PBX" -> generateBasicPbx(ctx, tenantId, app, e);
            case "OUTBOUND_DIALER" -> generateOutboundDialer(ctx, tenantId, app, e);
            case "VIRTUAL_RECEPTIONIST" -> generateVirtualReceptionist(ctx, tenantId, app, e);
            default -> generateDefault(ctx, tenantId);
        };

        // Wrap in full document
        String dialplanXml = wrapDocument(ctx, xml, app, e);

        // Send to PBX-Core for storage
        FreeSwitchDialplanRequest request = FreeSwitchDialplanRequest.builder()
                .tenantId(app.getTenant().getId())
                .subscriptionId(app.getSubscriptionId())
                .namespace(app.getNamespace())
                .context(ctx)
                .dialplanContent(dialplanXml)
                .productCode(app.getProductCode())
                .dedicatedInfrastructure(Boolean.TRUE.equals(app.getDedicatedInfrastructure()))
                .build();

        pbxCoreClient.storeFreeSwitchDialplan(request);

        log.info("FreeSWITCH dialplan stored: tenant={} context={} product={} lines={}",
                app.getNamespace(), ctx, app.getProductCode(), dialplanXml.lines().count());

        return dialplanXml;
    }

    // ═══════════════════════════════════════════════════════════
    // CONV_IVR — Bot handles full conversation via voice-brain
    // ═══════════════════════════════════════════════════════════

    private String generateConversationalIvr(String ctx, String tenantId, TenantApp app, PlanEntitlementResponse e) {
        String ws = voiceBrainWsUrl(tenantId, "CONV_IVR");
        return """
              <!-- CONVERSATIONAL IVR (context=%s) — voice-brain handles entire call -->
              <extension name="conv_ivr_inbound">
                <condition field="destination_number" expression="^(.*)$">
                  <action application="answer"/>
                  %s
                  %s
                  <!-- Bidirectional audio to voice-brain: STT→LLM→TTS→intent→escalation -->
                  <action application="socket" data="%s&amp;direction=INBOUND"/>
                  <!-- After voice-brain disconnects (escalation or end), fallback -->
                  %s
                </condition>
              </extension>
        """.formatted(
                ctx,
                cdrVars(tenantId, app),
                recording(e),
                ws,
                voicemailFallback(ctx, e)
        );
    }

    // ═══════════════════════════════════════════════════════════
    // VIRTUAL RECEPTIONIST — Bot takes messages, books appointments
    // ═══════════════════════════════════════════════════════════

    private String generateVirtualReceptionist(String ctx, String tenantId, TenantApp app, PlanEntitlementResponse e) {
        String ws = voiceBrainWsUrl(tenantId, "VIRTUAL_RECEPTIONIST");
        return """
              <!-- VIRTUAL RECEPTIONIST (context=%s) — bot greets, takes messages, books appointments -->
              <extension name="virtual_receptionist_inbound">
                <condition field="destination_number" expression="^(.*)$">
                  <action application="answer"/>
                  %s
                  %s
                  <action application="socket" data="%s&amp;direction=INBOUND"/>
                  %s
                </condition>
              </extension>
        """.formatted(
                ctx,
                cdrVars(tenantId, app),
                recording(e),
                ws,
                voicemailFallback(ctx, e)
        );
    }

    // ═══════════════════════════════════════════════════════════
    // AI CONTACT CENTER — Agent talks, voice-brain listens + assists
    // ═══════════════════════════════════════════════════════════

    private String generateAiContactCenter(String ctx, String tenantId, TenantApp app, PlanEntitlementResponse e) {
        String ws = voiceBrainWsUrl(tenantId, "AI_CC");
        return """
              <!-- AI CONTACT CENTER (context=%s) — fork audio to voice-brain for STT + sentiment, bridge to agent -->
              <extension name="ai_cc_inbound">
                <condition field="destination_number" expression="^(.*)$">
                  <action application="answer"/>
                  %s
                  %s
                  <!-- Fork audio to voice-brain (one-way listen for transcript + sentiment) -->
                  <action application="export" data="execute_on_answer=uuid_audio_fork ${uuid} %s&amp;direction=INBOUND both"/>
                  <!-- Bridge to agent/queue within tenant context -->
                  <action application="bridge" data="sofia/internal/${sip_h_X-Routing-Target}@%s"/>
                  %s
                </condition>
              </extension>
        """.formatted(
                ctx,
                cdrVars(tenantId, app),
                recording(e),
                ws,
                ctx,
                voicemailFallback(ctx, e)
        );
    }

    // ═══════════════════════════════════════════════════════════
    // BASIC PBX — Standard IVR + optional transcript via voice-brain
    // ═══════════════════════════════════════════════════════════

    private String generateBasicPbx(String ctx, String tenantId, TenantApp app, PlanEntitlementResponse e) {
        String ws = voiceBrainWsUrl(tenantId, "BASIC_PBX");
        boolean aiEnabled = e.aiBotEnabled() || e.aiTranscriptionEnabled();

        return """
              <!-- BASIC PBX (context=%s) — DTMF IVR menu, optional AI transcript -->
              <extension name="basic_pbx_inbound">
                <condition field="destination_number" expression="^(.*)$">
                  <action application="answer"/>
                  %s
                  %s
                  %s
                  <!-- Bridge to routing target (extension, queue, ring group) -->
                  <action application="bridge" data="sofia/internal/${sip_h_X-Routing-Target}@%s"/>
                  %s
                </condition>
              </extension>

              <!-- Internal extension dialing -->
              <extension name="basic_pbx_extensions">
                <condition field="destination_number" expression="^(1\\d{2})$">
                  <action application="bridge" data="sofia/internal/$1@%s"/>
                </condition>
              </extension>
        """.formatted(
                ctx,
                cdrVars(tenantId, app),
                recording(e),
                aiEnabled ? """
                  <!-- Fork audio to voice-brain for live transcript -->
                  <action application="export" data="execute_on_answer=uuid_audio_fork ${uuid} %s&amp;direction=INBOUND both"/>
                """.formatted(ws) : "<!-- AI not enabled -->",
                ctx,
                voicemailFallback(ctx, e),
                ctx
        );
    }

    // ═══════════════════════════════════════════════════════════
    // OUTBOUND DIALER — Bot runs campaign, DialerEngine originates
    // ═══════════════════════════════════════════════════════════

    private String generateOutboundDialer(String ctx, String tenantId, TenantApp app, PlanEntitlementResponse e) {
        // Outbound: DialerEngine sets X-Campaign-ID, X-Contact-ID, X-Bot-ID via ESL originate
        return """
              <!-- OUTBOUND DIALER (context=%s) — voice-brain runs campaign script -->
              <extension name="outbound_dialer">
                <condition field="destination_number" expression="^(.*)$">
                  <action application="answer"/>
                  %s
                  %s
                  %s
                  <!-- voice-brain fetches campaign + contact context from PBX-Core -->
                  <action application="socket" data="%s/audio/${uuid}?tenant_id=%s&amp;product_code=OUTBOUND_DIALER&amp;bot_id=${sip_h_X-Bot-ID}&amp;campaign_id=${sip_h_X-Campaign-ID}&amp;contact_id=${sip_h_X-Contact-ID}&amp;direction=OUTBOUND"/>
                  <action application="hangup" data="NORMAL_CLEARING"/>
                </condition>
              </extension>

              <!-- Callback: when lead calls back the campaign DID, route into tenant queue -->
              <extension name="outbound_dialer_callback">
                <condition field="${sip_h_X-Routing-Type}" expression="CALLBACK">
                  <action application="answer"/>
                  %s
                  <action application="bridge" data="sofia/internal/${sip_h_X-Routing-Target}@%s"/>
                  <action application="hangup"/>
                </condition>
              </extension>
        """.formatted(
                ctx,
                cdrVars(tenantId, app),
                recording(e),
                e.amdEnabled() ? """
                  <action application="amd"/>
                  <action application="set" data="CDR(amd_result)=${amd_result}"/>
                """ : "<!-- AMD not enabled -->",
                voiceBrainUrl, tenantId,
                cdrVars(tenantId, app),
                ctx
        );
    }

    // ═══════════════════════════════════════════════════════════
    // DEFAULT — Unknown product, play message and hangup
    // ═══════════════════════════════════════════════════════════

    private String generateDefault(String ctx, String tenantId) {
        return """
              <!-- DEFAULT (context=%s) — unknown product, fallback handler -->
              <extension name="default_handler">
                <condition field="destination_number" expression="^(.*)$">
                  <action application="answer"/>
                  <action application="playback" data="ivr/ivr-welcome.wav"/>
                  <action application="hangup" data="NORMAL_CLEARING"/>
                </condition>
              </extension>
        """.formatted(ctx);
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════

    private String wrapDocument(String ctx, String extensionsXml, TenantApp app, PlanEntitlementResponse e) {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <!--
              Tenant: %s (%s) | Product: %s | Plan: %s
              Context: %s
              Generated: %s
              AI: %s | Recording: %s | Agents: %d | Queues: %d
              voice-brain: %s
            -->
            <document type="freeswitch/xml">
              <section name="dialplan" description="Dalai LLAMA - %s">
                <context name="%s">
                  %s
                </context>
              </section>
            </document>
        """.formatted(
                app.getNamespace(), app.getTenant().getId(), app.getProductCode(), app.getPlanCode(),
                ctx,
                Instant.now(),
                e.aiBotEnabled(), e.recordingEnabled(), e.maxAgents(), e.maxQueues(),
                voiceBrainUrl,
                app.getNamespace(), ctx, extensionsXml
        );
    }

    /**
     * Build voice-brain WebSocket URL.
     * bot_id comes from SIP header X-Bot-ID (set by Kamailio from /authorize/inbound response).
     */
    private String voiceBrainWsUrl(String tenantId, String productCode) {
        return "%s/audio/${uuid}?tenant_id=%s&amp;product_code=%s&amp;bot_id=${sip_h_X-Bot-ID}".formatted(
                voiceBrainUrl, tenantId, productCode);
    }

    private String cdrVars(String tenantId, TenantApp app) {
        return """
                  <action application="set" data="tenant_id=%s"/>
                  <action application="set" data="subscription_id=%s"/>
                  <action application="set" data="product_code=%s"/>
                  <action application="set" data="accountcode=%s"/>
        """.formatted(tenantId, app.getSubscriptionId(), app.getProductCode(), tenantId);
    }

    private String recording(PlanEntitlementResponse e) {
        if (e.recordingEnabled()) {
            return """
                  <action application="set" data="RECORD_STEREO=true"/>
                  <action application="set" data="execute_on_answer=record_session /recordings/${uuid}.wav"/>
            """;
        }
        return "<!-- recording not enabled -->";
    }

    private String voicemailFallback(String ctx, PlanEntitlementResponse e) {
        if (e.voicemailEnabled()) {
            return """
                  <!-- Fallback to voicemail if no answer -->
                  <action application="voicemail" data="default ${tenant_id}"/>
            """;
        }
        return """
                  <action application="hangup" data="NORMAL_CLEARING"/>
        """;
    }
}