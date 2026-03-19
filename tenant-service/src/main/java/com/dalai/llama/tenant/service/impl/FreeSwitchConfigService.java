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

/**
 * FreeSWITCH Dialplan Configuration — generates XML dialplan per tenant.
 *
 * KEY CHANGE FROM PREVIOUS VERSION:
 *   OLD: Generated Asterisk extensions.conf format with AGI() calls
 *   NEW: Generates FreeSWITCH XML format with mod_audio_stream WebSocket to voice-brain
 *
 * For AI products (CONV_IVR, VIRTUAL_RECEPTIONIST, AI_CC, OUTBOUND_DIALER):
 *   FreeSWITCH connects bidirectional audio to voice-brain via WebSocket:
 *     <action application="socket" data="ws://voice-brain:8600/audio/${uuid}?tenant_id=...&bot_id=..."/>
 *
 *   voice-brain handles everything: STT, LLM, TTS, intent detection, escalation.
 *   When voice-brain escalates, it calls PBX-Core /internal/ai/escalation
 *   → PBX-Core sends ESL uuid_transfer to FreeSWITCH → call routes to queue/agent.
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

    @Value("${dalaillama.voicebrain.url:ws://127.0.0.1:8600}")
    private String voiceBrainUrl;

    public String configureForSubscription(TenantApp app, PlanEntitlementResponse e) {
        log.info("Generating FreeSWITCH XML dialplan: tenant={} product={}",
                app.getNamespace(), app.getProductCode());

        String ctx = "tenant_" + app.getNamespace();
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

        log.info("FreeSWITCH dialplan stored: tenant={} product={} lines={}",
                app.getNamespace(), app.getProductCode(), dialplanXml.lines().count());

        return dialplanXml;
    }

    // ═══════════════════════════════════════════════════════════
    // CONV_IVR — Bot handles full conversation via voice-brain
    // ═══════════════════════════════════════════════════════════

    private String generateConversationalIvr(String ctx, String tenantId, TenantApp app, PlanEntitlementResponse e) {
        String ws = voiceBrainWsUrl(tenantId, "CONV_IVR");
        return """
              <!-- CONVERSATIONAL IVR — voice-brain handles entire call -->
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
              <!-- VIRTUAL RECEPTIONIST — bot greets, takes messages, books appointments -->
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
              <!-- AI CONTACT CENTER — fork audio to voice-brain for STT + sentiment, bridge to agent -->
              <extension name="ai_cc_inbound">
                <condition field="destination_number" expression="^(.*)$">
                  <action application="answer"/>
                  %s
                  %s
                  <!-- Fork audio to voice-brain (one-way listen for transcript + sentiment) -->
                  <action application="export" data="execute_on_answer=uuid_audio_fork ${uuid} %s&amp;direction=INBOUND both"/>
                  <!-- Bridge to agent/queue -->
                  <action application="bridge" data="sofia/internal/${sip_h_X-Routing-Target}@${sip_h_X-Tenant-ID}"/>
                  %s
                </condition>
              </extension>
        """.formatted(
                cdrVars(tenantId, app),
                recording(e),
                ws,
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
              <!-- BASIC PBX — DTMF IVR menu, optional AI transcript -->
              <extension name="basic_pbx_inbound">
                <condition field="destination_number" expression="^(.*)$">
                  <action application="answer"/>
                  %s
                  %s
                  %s
                  <!-- Bridge to routing target (extension, queue, ring group) -->
                  <action application="bridge" data="sofia/internal/${sip_h_X-Routing-Target}@${sip_h_X-Tenant-ID}"/>
                  %s
                </condition>
              </extension>

              <!-- Internal extension dialing -->
              <extension name="basic_pbx_extensions">
                <condition field="destination_number" expression="^(1\\d{2})$">
                  <action application="bridge" data="sofia/internal/$1@${sip_h_X-Tenant-ID}"/>
                </condition>
              </extension>
        """.formatted(
                cdrVars(tenantId, app),
                recording(e),
                aiEnabled ? """
                  <!-- Fork audio to voice-brain for live transcript -->
                  <action application="export" data="execute_on_answer=uuid_audio_fork ${uuid} %s&amp;direction=INBOUND both"/>
                """.formatted(ws) : "<!-- AI not enabled -->",
                voicemailFallback(ctx, e)
        );
    }

    // ═══════════════════════════════════════════════════════════
    // OUTBOUND DIALER — Bot runs campaign, DialerEngine originates
    // ═══════════════════════════════════════════════════════════

    private String generateOutboundDialer(String ctx, String tenantId, TenantApp app, PlanEntitlementResponse e) {
        // Outbound: DialerEngine sets X-Campaign-ID, X-Contact-ID, X-Bot-ID via ESL originate
        return """
              <!-- OUTBOUND DIALER — voice-brain runs campaign script -->
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

              <!-- Callback: when lead calls back the campaign DID -->
              <extension name="outbound_dialer_callback">
                <condition field="${sip_h_X-Routing-Type}" expression="CALLBACK">
                  <action application="answer"/>
                  %s
                  <action application="queue" data="%s-callback"/>
                  <action application="hangup"/>
                </condition>
              </extension>
        """.formatted(
                cdrVars(tenantId, app),
                recording(e),
                e.amdEnabled() ? """
                  <action application="amd"/>
                  <action application="set" data="CDR(amd_result)=${amd_result}"/>
                """ : "<!-- AMD not enabled -->",
                voiceBrainUrl, tenantId,
                cdrVars(tenantId, app),
                app.getNamespace()
        );
    }

    // ═══════════════════════════════════════════════════════════
    // DEFAULT — Unknown product, play message and hangup
    // ═══════════════════════════════════════════════════════════

    private String generateDefault(String ctx, String tenantId) {
        return """
              <extension name="default_handler">
                <condition field="destination_number" expression="^(.*)$">
                  <action application="answer"/>
                  <action application="playback" data="ivr/ivr-welcome.wav"/>
                  <action application="hangup" data="NORMAL_CLEARING"/>
                </condition>
              </extension>
        """;
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════

    private String wrapDocument(String ctx, String extensionsXml, TenantApp app, PlanEntitlementResponse e) {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <!--
              Tenant: %s | Product: %s | Plan: %s
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
                app.getNamespace(), app.getProductCode(), app.getPlanCode(),
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