package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreePBXConfigService {

    private final KubernetesConfigDiscoveryService configDiscovery;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    public String configureForSubscription(TenantApp app, PlanEntitlementResponse e) {
        log.info("Configuring FreePBX for {} - product: {}", app.getSubscriptionId(), app.getProductCode());

        String ctx = "tenant_" + app.getNamespace();
        boolean dedicated = Boolean.TRUE.equals(app.getDedicatedInfrastructure());
        String aiAgiUrl = configDiscovery.getAiAgiUrl(dedicated, app.getNamespace());

        StringBuilder config = new StringBuilder();
        config.append(generateHeader(app, e));

        String dialplan = switch (app.getProductCode()) {
            case "AI_CC" -> generateAiContactCenter(app, e, ctx, aiAgiUrl);
            case "CONV_IVR" -> generateConversationalIvr(app, e, ctx, aiAgiUrl);
            case "BASIC_PBX" -> generateBasicPbx(app, e, ctx);
            case "OUTBOUND_DIALER" -> generateOutboundDialer(app, e, ctx, aiAgiUrl);
            case "VIRTUAL_RECEPTIONIST" -> generateVirtualReceptionist(app, e, ctx, aiAgiUrl);
            default -> generateDefault(app, ctx);
        };

        config.append(dialplan);

        if (e.voicemailEnabled()) config.append(generateVoicemail(app, ctx, e));
        if (e.maxRingGroups() > 0) config.append(generateRingGroups(ctx, e.maxRingGroups()));
        if (e.bargeEnabled() || e.whisperEnabled() || e.listenEnabled())
            config.append(generateSupervisorFeatures(ctx, e));

        log.info("FreePBX config generated: {} lines", config.toString().lines().count());
        return config.toString();
    }

    private String generateHeader(TenantApp app, PlanEntitlementResponse e) {
        return String.format("""
                ; ================================================================
                ; FreeSWITCH Dialplan - Tenant: %s
                ; Product: %s | Plan: %s | Tier: %s
                ; Generated: %s
                ; AI: %s | Recording: %s | Agents: %d | Queues: %d
                ; ================================================================
                
                """, app.getNamespace(), app.getProductCode(), app.getPlanCode(), app.getPlanTier(),
                Instant.now(), e.aiBotEnabled(), e.recordingEnabled(), e.maxAgents(), e.maxQueues());
    }

    /**
     * Common CDR setup block - added to every context
     */
    private String generateCdrSetup(TenantApp app, PlanEntitlementResponse e) {
        return String.format("""
                 same => n,Set(TENANT_ID=%s)
                 same => n,Set(SUBSCRIPTION_ID=%s)
                 same => n,Set(PRODUCT_CODE=%s)
                 same => n,Set(RATE_PER_MIN=%s)
                 same => n,Set(CDR(tenant_id)=${TENANT_ID})
                 same => n,Set(CDR(subscription_id)=${SUBSCRIPTION_ID})
                 same => n,Set(CDR(product_code)=${PRODUCT_CODE})
                 same => n,Set(CDR(rate_per_minute)=${RATE_PER_MIN})
                """,
                app.getTenant().getId(),
                app.getSubscriptionId(),
                app.getProductCode(),
                e.ratePerMinuteInbound() != null ? e.ratePerMinuteInbound() : "1.5");
    }

    /**
     * CDR finalization - set AI minutes and other final values
     */
    private String generateCdrFinalize() {
        return """
                 same => n,Set(CDR(ai_minutes)=${AI_MINUTES_USED})
                 same => n,Set(CDR(sentiment_score)=${SENTIMENT_SCORE})
                """;
    }

    private String generateAiContactCenter(TenantApp app, PlanEntitlementResponse e, String ctx, String aiAgi) {
        String cdrSetup = generateCdrSetup(app, e);

        return String.format("""
                ; ==================== AI CONTACT CENTER ====================
                
                [%s]
                exten => _X.,1,NoOp(AI Contact Center: ${EXTEN})
                %s
                 same => n,Set(AI_MINUTES_USED=0)
                 same => n,Answer()
                 same => n,Goto(%s-incoming,${EXTEN},1)
                
                [%s-incoming]
                exten => _X.,1,Set(CALL_ID=${UNIQUEID})
                 same => n,Set(CALLER_ID=${CALLERID(num)})
                 same => n,Set(AI_START=${EPOCH})
                 %s
                 same => n,AGI(%s/greeting,${TENANT_ID},${CALL_ID})
                 same => n,Set(AI_MINUTES_USED=$[${AI_MINUTES_USED} + ($[${EPOCH} - ${AI_START}] / 60)])
                 same => n,Set(AI_INTENT=${AI_INTENT})
                 same => n,GotoIf($["${AI_INTENT}"="AGENT"]?%s-queue,${EXTEN},1)
                 same => n,GotoIf($["${AI_INTENT}"="SELF_SERVICE"]?%s-selfservice,s,1)
                 same => n,Goto(%s-conversation,${EXTEN},1)
                
                [%s-conversation]
                exten => _X.,1,Set(CONV_START=${EPOCH})
                 same => n,AGI(%s/conversation,${TENANT_ID},${CALL_ID})
                 same => n,Set(AI_MINUTES_USED=$[${AI_MINUTES_USED} + ($[${EPOCH} - ${CONV_START}] / 60)])
                 same => n,GotoIf($["${AI_ACTION}"="TRANSFER"]?%s-queue,${EXTEN},1)
                 same => n,GotoIf($["${AI_ACTION}"="END"]?hangup)
                 same => n,Goto(1)
                 same => n(hangup),Set(CDR(ai_minutes)=${AI_MINUTES_USED})
                 same => n,Playback(goodbye)
                 same => n,Hangup()
                
                [%s-selfservice]
                exten => s,1,Set(SS_START=${EPOCH})
                 same => n,AGI(%s/selfservice,${TENANT_ID},${CALL_ID})
                 same => n,Set(AI_MINUTES_USED=$[${AI_MINUTES_USED} + ($[${EPOCH} - ${SS_START}] / 60)])
                 same => n,GotoIf($["${RESOLVED}"="true"]?done)
                 same => n,Goto(%s-queue,s,1)
                 same => n(done),Set(CDR(ai_minutes)=${AI_MINUTES_USED})
                 same => n,Playback(goodbye)
                 same => n,Hangup()
                
                [%s-queue]
                exten => _X.,1,NoOp(Routing to Queue)
                 same => n,Set(QUEUE=%s-default)
                 same => n,Set(CDR(ai_minutes)=${AI_MINUTES_USED})
                 %s
                 same => n,Queue(${QUEUE},tTkK,,,300)
                 same => n,GotoIf($["${QUEUESTATUS}"="TIMEOUT"]?%s-voicemail,${EXTEN},1)
                 same => n,Hangup()
                
                """, ctx, cdrSetup, ctx,
                ctx, e.recordingEnabled() ? "same => n,MixMonitor(${UNIQUEID}.wav,b)" : "; no recording",
                aiAgi, ctx, ctx, ctx,
                ctx, aiAgi, ctx,
                ctx, aiAgi, ctx,
                ctx, app.getNamespace(), generateQueueFeatures(e), ctx);
    }

    private String generateConversationalIvr(TenantApp app, PlanEntitlementResponse e, String ctx, String aiAgi) {
        String cdrSetup = generateCdrSetup(app, e);

        return String.format("""
                ; ==================== CONVERSATIONAL IVR ====================
                
                [%s]
                exten => _X.,1,NoOp(Conversational IVR: ${EXTEN})
                %s
                 same => n,Set(AI_MINUTES_USED=0)
                 same => n,Answer()
                 same => n,Goto(%s-ivr,${EXTEN},1)
                
                [%s-ivr]
                exten => _X.,1,Set(CALL_ID=${UNIQUEID})
                 same => n,Set(SESSION_START=${EPOCH})
                 %s
                 same => n(loop),Set(TURN_START=${EPOCH})
                 same => n,AGI(%s/conversational-ivr,${TENANT_ID},${CALL_ID})
                 same => n,Set(AI_MINUTES_USED=$[${AI_MINUTES_USED} + ($[${EPOCH} - ${TURN_START}] / 60)])
                 same => n,Set(INTENT=${AI_INTENT})
                 same => n,Set(CONFIDENCE=${AI_CONFIDENCE})
                 same => n,GotoIf($["${INTENT}"="END"]?end)
                 same => n,GotoIf($["${INTENT}"="TRANSFER"]?transfer)
                 same => n,GotoIf($[${CONFIDENCE}<0.5]?clarify)
                 same => n,Goto(loop)
                 same => n(clarify),AGI(%s/clarify,${TENANT_ID},${CALL_ID})
                 same => n,Goto(loop)
                 same => n(transfer),Goto(%s-handoff,${EXTEN},1)
                 same => n(end),Set(CDR(ai_minutes)=${AI_MINUTES_USED})
                 same => n,Playback(goodbye)
                 same => n,Hangup()
                
                [%s-handoff]
                exten => _X.,1,Set(CDR(ai_minutes)=${AI_MINUTES_USED})
                 same => n,Playback(please-hold)
                 same => n,Queue(%s-handoff,tT,,,180)
                 same => n,Hangup()
                
                %s
                """, ctx, cdrSetup, ctx,
                ctx, e.recordingEnabled() ? "same => n,MixMonitor(${UNIQUEID}.wav,b)" : "",
                aiAgi, aiAgi, ctx,
                ctx, app.getNamespace(),
                e.ivrMultiLanguageEnabled() ? generateMultiLanguage(ctx, aiAgi) : "");
    }

    private String generateBasicPbx(TenantApp app, PlanEntitlementResponse e, String ctx) {
        String cdrSetup = generateCdrSetup(app, e);

        return String.format("""
                ; ==================== BASIC PBX ====================
                
                [%s]
                exten => _X.,1,NoOp(Basic PBX: ${EXTEN})
                %s
                 same => n,Answer()
                 same => n,Goto(%s-ivr,s,1)
                
                [%s-ivr]
                exten => s,1,NoOp(Main IVR)
                 %s
                 same => n(menu),Background(welcome)
                 same => n,WaitExten(5)
                
                exten => 1,1,Goto(%s-sales,s,1)
                exten => 2,1,Goto(%s-support,s,1)
                exten => 3,1,Goto(%s-directory,s,1)
                exten => 0,1,Goto(%s-operator,s,1)
                exten => i,1,Playback(invalid)
                 same => n,Goto(s,menu)
                exten => t,1,Goto(%s-operator,s,1)
                
                [%s-extensions]
                exten => _1XX,1,Dial(PJSIP/${EXTEN}@%s,30,tT)
                 same => n,GotoIf($["${DIALSTATUS}"="NOANSWER"]?%s-voicemail,${EXTEN},1)
                 same => n,Hangup()
                
                [%s-sales]
                exten => s,1,Queue(%s-sales,tT,,,120)
                 same => n,Goto(%s-voicemail,sales,1)
                
                [%s-support]
                exten => s,1,Queue(%s-support,tT,,,180)
                 same => n,Goto(%s-voicemail,support,1)
                
                [%s-operator]
                exten => s,1,Queue(%s-operator,tT,,,60)
                 same => n,Hangup()
                
                [%s-directory]
                exten => s,1,Directory(%s,%s,f)
                 same => n,Hangup()
                
                """, ctx, cdrSetup, ctx,
                ctx, e.recordingEnabled() ? "same => n,MixMonitor(${UNIQUEID}.wav,b)" : "",
                ctx, ctx, ctx, ctx, ctx,
                ctx, app.getNamespace(), ctx,
                ctx, app.getNamespace(), ctx,
                ctx, app.getNamespace(), ctx,
                ctx, app.getNamespace(),
                ctx, ctx, ctx);
    }

    private String generateOutboundDialer(TenantApp app, PlanEntitlementResponse e, String ctx, String aiAgi) {
        // Outbound uses outbound rate
        String cdrSetup = String.format("""
                 same => n,Set(TENANT_ID=%s)
                 same => n,Set(SUBSCRIPTION_ID=%s)
                 same => n,Set(PRODUCT_CODE=%s)
                 same => n,Set(RATE_PER_MIN=%s)
                 same => n,Set(CDR(tenant_id)=${TENANT_ID})
                 same => n,Set(CDR(subscription_id)=${SUBSCRIPTION_ID})
                 same => n,Set(CDR(product_code)=${PRODUCT_CODE})
                 same => n,Set(CDR(rate_per_minute)=${RATE_PER_MIN})
                 same => n,Set(CDR(direction)=OUTBOUND)
                """,
                app.getTenant().getId(),
                app.getSubscriptionId(),
                app.getProductCode(),
                e.ratePerMinuteOutbound() != null ? e.ratePerMinuteOutbound() : "1.5");

        return String.format("""
                ; ==================== OUTBOUND DIALER ====================
                
                [%s]
                exten => _X.,1,NoOp(Dialer Callback: ${EXTEN})
                %s
                 same => n,Answer()
                 same => n,Goto(%s-callback,${EXTEN},1)
                
                [%s-outbound]
                exten => _X.,1,NoOp(Outbound: ${EXTEN})
                %s
                 same => n,Set(CAMPAIGN_ID=${CAMPAIGN_ID})
                 same => n,Set(LEAD_ID=${LEAD_ID})
                 same => n,Set(CDR(campaign_id)=${CAMPAIGN_ID})
                 same => n,Set(CDR(lead_id)=${LEAD_ID})
                 %s
                 %s
                 same => n,Dial(PJSIP/${EXTEN}@epsilon,60,tTg)
                 same => n,Set(CDR(dial_status)=${DIALSTATUS})
                 same => n,AGI(%s/dialer-result,${TENANT_ID},${CAMPAIGN_ID},${LEAD_ID},${DIALSTATUS})
                 same => n,Hangup()
                
                [%s-amd]
                exten => _X.,1,AMD()
                 same => n,Set(AMD=${AMDSTATUS})
                 same => n,Set(CDR(amd_result)=${AMDSTATUS})
                 same => n,GotoIf($["${AMD}"="MACHINE"]?machine)
                 same => n,Goto(%s-agent,${EXTEN},1)
                 same => n(machine),Playback(vm-message)
                 same => n,Hangup()
                
                [%s-agent]
                exten => _X.,1,Playback(please-hold)
                 same => n,Queue(%s-dialer,tT,,,30)
                 same => n,Hangup()
                
                [%s-callback]
                exten => _X.,1,Playback(callback-welcome)
                 same => n,Queue(%s-callback,tT,,,120)
                 same => n,Hangup()
                
                %s
                """, ctx, cdrSetup, ctx,
                ctx, cdrSetup,
                e.recordingEnabled() ? "same => n,MixMonitor(${UNIQUEID}.wav,b)" : "",
                e.amdEnabled() ? "same => n,Goto(" + ctx + "-amd,${EXTEN},1)" : "",
                aiAgi,
                ctx, ctx,
                ctx, app.getNamespace(),
                ctx, app.getNamespace(),
                e.dncManagementEnabled() ? generateDncCheck(ctx) : "");
    }

    private String generateVirtualReceptionist(TenantApp app, PlanEntitlementResponse e, String ctx, String aiAgi) {
        String cdrSetup = generateCdrSetup(app, e);

        return String.format("""
                ; ==================== VIRTUAL RECEPTIONIST ====================
                
                [%s]
                exten => _X.,1,NoOp(Virtual Receptionist: ${EXTEN})
                %s
                 same => n,Set(AI_MINUTES_USED=0)
                 same => n,Answer()
                 same => n,Goto(%s-receptionist,${EXTEN},1)
                
                [%s-receptionist]
                exten => _X.,1,Set(CALL_ID=${UNIQUEID})
                 %s
                 same => n,Set(GREET_START=${EPOCH})
                 same => n,AGI(%s/receptionist-greeting,${TENANT_ID},${CALL_ID})
                 same => n,Set(AI_MINUTES_USED=$[${AI_MINUTES_USED} + ($[${EPOCH} - ${GREET_START}] / 60)])
                 same => n(loop),Set(TURN_START=${EPOCH})
                 same => n,AGI(%s/receptionist,${TENANT_ID},${CALL_ID})
                 same => n,Set(AI_MINUTES_USED=$[${AI_MINUTES_USED} + ($[${EPOCH} - ${TURN_START}] / 60)])
                 same => n,Set(INTENT=${AI_INTENT})
                 same => n,GotoIf($["${INTENT}"="APPOINTMENT"]?%s-appointment,s,1)
                 same => n,GotoIf($["${INTENT}"="MESSAGE"]?%s-message,s,1)
                 same => n,GotoIf($["${INTENT}"="TRANSFER"]?%s-transfer,s,1)
                 same => n,GotoIf($["${INTENT}"="END"]?end)
                 same => n,Goto(loop)
                 same => n(end),Set(CDR(ai_minutes)=${AI_MINUTES_USED})
                 same => n,Playback(goodbye)
                 same => n,Hangup()
                
                [%s-appointment]
                exten => s,1,Set(APPT_START=${EPOCH})
                 same => n,AGI(%s/appointment,${TENANT_ID},${CALL_ID})
                 same => n,Set(AI_MINUTES_USED=$[${AI_MINUTES_USED} + ($[${EPOCH} - ${APPT_START}] / 60)])
                 same => n,Goto(%s-receptionist,s,loop)
                
                [%s-message]
                exten => s,1,AGI(%s/message-intro,${TENANT_ID},${CALL_ID})
                 same => n,Record(${TENANT_ID}/msg/${UNIQUEID}:wav,5,60,kq)
                 same => n,Set(TRANS_START=${EPOCH})
                 same => n,AGI(%s/transcribe,${TENANT_ID},${CALL_ID})
                 same => n,Set(AI_MINUTES_USED=$[${AI_MINUTES_USED} + ($[${EPOCH} - ${TRANS_START}] / 60)])
                 same => n,Playback(message-received)
                 same => n,Goto(%s-receptionist,s,loop)
                
                [%s-transfer]
                exten => s,1,Set(TARGET=${AI_TARGET})
                 same => n,Set(CDR(ai_minutes)=${AI_MINUTES_USED})
                 same => n,Playback(please-hold)
                 same => n,Dial(PJSIP/${TARGET}@%s,30,tT)
                 same => n,Goto(%s-voicemail,${TARGET},1)
                
                """, ctx, cdrSetup, ctx,
                ctx, e.recordingEnabled() ? "same => n,MixMonitor(${UNIQUEID}.wav,b)" : "",
                aiAgi, aiAgi, ctx, ctx, ctx,
                ctx, aiAgi, ctx,
                ctx, aiAgi, aiAgi, ctx,
                ctx, app.getNamespace(), ctx);
    }

    private String generateDefault(TenantApp app, String ctx) {
        return String.format("""
                [%s]
                exten => _X.,1,NoOp(Default: ${EXTEN})
                 same => n,Set(TENANT_ID=%s)
                 same => n,Set(CDR(tenant_id)=${TENANT_ID})
                 same => n,Answer()
                 same => n,Playback(welcome)
                 same => n,Hangup()
                """, ctx, app.getTenant().getId());
    }

    private String generateQueueFeatures(PlanEntitlementResponse e) {
        StringBuilder sb = new StringBuilder();
        if (e.bargeEnabled()) sb.append(" same => n,Set(BARGE=1)\n");
        if (e.whisperEnabled()) sb.append(" same => n,Set(WHISPER=1)\n");
        if (e.listenEnabled()) sb.append(" same => n,Set(LISTEN=1)\n");
        return sb.toString();
    }

    private String generateVoicemail(TenantApp app, String ctx, PlanEntitlementResponse e) {
        return String.format("""
                
                [%s-voicemail]
                exten => _X.,1,VoiceMail(${EXTEN}@%s,u)
                 %s
                 same => n,Hangup()
                
                exten => *98,1,VoiceMailMain(@%s)
                 same => n,Hangup()
                """, ctx, app.getNamespace(),
                e.voicemailTranscriptionEnabled() ?
                        "same => n,AGI(agi://ai-service:4573/transcribe-voicemail,${TENANT_ID},${EXTEN})" : "",
                app.getNamespace());
    }

    private String generateRingGroups(String ctx, int maxGroups) {
        StringBuilder sb = new StringBuilder("\n[" + ctx + "-ringgroups]\n");
        for (int i = 1; i <= Math.min(maxGroups, 5); i++) {
            sb.append(String.format("""
                exten => %d00,1,Dial(PJSIP/member1&PJSIP/member2,30,tT)
                 same => n,Hangup()
                """, i));
        }
        return sb.toString();
    }

    private String generateSupervisorFeatures(String ctx, PlanEntitlementResponse e) {
        StringBuilder sb = new StringBuilder("\n[" + ctx + "-supervisor]\n");
        if (e.listenEnabled()) {
            sb.append("exten => _*1X.,1,ChanSpy(PJSIP/${EXTEN:2},q)\n");
        }
        if (e.whisperEnabled()) {
            sb.append("exten => _*2X.,1,ChanSpy(PJSIP/${EXTEN:2},qw)\n");
        }
        if (e.bargeEnabled()) {
            sb.append("exten => _*3X.,1,ChanSpy(PJSIP/${EXTEN:2},qB)\n");
        }
        return sb.toString();
    }

    private String generateMultiLanguage(String ctx, String aiAgi) {
        return String.format("""
                
                [%s-language]
                exten => s,1,AGI(%s/detect-language,${TENANT_ID},${CALL_ID})
                 same => n,Set(CHANNEL(language)=${DETECTED_LANG})
                 same => n,Return()
                """, ctx, aiAgi);
    }

    private String generateDncCheck(String ctx) {
        return String.format("""
                
                [%s-dnc]
                exten => _X.,1,Set(DNC=${SHELL(curl -s localhost:8080/api/dnc/${EXTEN})})
                 same => n,GotoIf($["${DNC}"="true"]?blocked)
                 same => n,Return()
                 same => n(blocked),Set(CDR(dnc)=1)
                 same => n,Hangup()
                """, ctx);
    }
}