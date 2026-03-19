package com.dalai.llama.pbx.core.service.call;


import com.dalai.llama.pbx.core.esl.EslCommandExecutor;
import com.dalai.llama.pbx.core.redis.ActiveCallTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Call control — translates Agent UI / voice-brain HTTP requests into ESL commands.
 *
 * This is the ONLY path to FreeSWITCH call control. Neither Agent UI nor
 * voice-brain talk to FreeSWITCH directly — they call PBX-Core HTTP APIs,
 * which come here, which calls EslCommandExecutor.
 *
 * Called by:
 *   - CallController    → POST /api/v1/calls/{callId}/answer|hold|transfer|hangup|dtmf
 *   - CallController    → POST /api/v1/calls/{callId}/interrupt  (voice-brain barge-in)
 *   - CallController    → POST /api/v1/calls/originate
 *   - DialerEngine      → originate outbound campaign calls
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallControlService {

    private final EslCommandExecutor esl;
    private final ActiveCallTracker callTracker;

    /**
     * Originate a new outbound call.
     *
     * @param from       caller ID to display
     * @param to         destination number or SIP URI
     * @param tenantId   tenant UUID
     * @param context    FreeSWITCH context (e.g., "tenant_acme")
     * @param variables  extra channel variables (product_code, campaign_id, etc.)
     * @return           FreeSWITCH Job-UUID
     */
    public String originate(String from, String to, UUID tenantId,
                            String context, Map<String, String> variables) {
        StringBuilder vars = new StringBuilder();
        vars.append("{origination_caller_id_number=").append(from);
        vars.append(",tenant_id=").append(tenantId);
        if (variables != null) {
            variables.forEach((k, v) -> vars.append(",").append(k).append("=").append(v));
        }
        vars.append("}");

        String dialString = vars + "sofia/internal/" + to + "@" + context + " &park";
        log.info("Originating: {}", dialString);

        return esl.originate(dialString);
    }

    public String answer(String callId) {
        log.info("Answering call: {}", callId);
        callTracker.updateStatus(callId, "ANSWERED");
        return esl.answer(callId);
    }

    public String hold(String callId) {
        log.info("Toggling hold: {}", callId);
        return esl.hold(callId);
    }

    /**
     * Transfer call to a new destination.
     *
     * @param callId      active call UUID
     * @param destination target — extension ("1002"), queue ("queue_sales"),
     *                    or external number ("+1555999")
     * @param context     FreeSWITCH context for the transfer
     */
    public String transfer(String callId, String destination, String context) {
        String transferTarget = destination + " XML " + (context != null ? context : "default");
        log.info("Transferring {} → {}", callId, transferTarget);
        callTracker.updateStatus(callId, "TRANSFERRED");
        return esl.transfer(callId, transferTarget);
    }

    public String hangup(String callId) {
        log.info("Hanging up: {}", callId);
        return esl.hangup(callId);
    }

    public String hangup(String callId, String cause) {
        log.info("Hanging up: {} cause={}", callId, cause);
        return esl.hangup(callId, cause);
    }

    public String sendDtmf(String callId, String digits) {
        log.debug("Sending DTMF {} to {}", digits, callId);
        return esl.sendDtmf(callId, digits);
    }

    /**
     * Interrupt TTS playback — called by voice-brain when it detects
     * caller speaking during bot TTS output (barge-in).
     *
     * voice-brain → POST /api/v1/calls/{callId}/interrupt → this method → ESL uuid_break
     */
    public String interrupt(String callId) {
        log.info("Interrupting playback: {}", callId);
        return esl.interrupt(callId);
    }

    /**
     * Start call recording.
     * Recording path convention: /recordings/{tenantId}/{date}/{callId}.wav
     */
    public String startRecording(String callId, UUID tenantId) {
        String date = java.time.LocalDate.now().toString();
        String path = "/recordings/" + tenantId + "/" + date + "/" + callId + ".wav";
        log.info("Starting recording: {} → {}", callId, path);
        callTracker.updateField(callId, "recording_path", path);
        return esl.startRecording(callId, path);
    }

    public String stopRecording(String callId, String path) {
        log.info("Stopping recording: {} path={}", callId, path);
        return esl.stopRecording(callId, path);
    }

    /**
     * Bridge two call legs together — used for attended transfer completion.
     */
    public String bridge(String callId, String otherCallId) {
        log.info("Bridging {} ↔ {}", callId, otherCallId);
        return esl.bridge(callId, otherCallId);
    }

    /**
     * Get all active calls for a tenant — reads from Redis ActiveCallTracker.
     */
    public List<Map<Object, Object>> getActiveCalls(UUID tenantId) {
        return callTracker.getActiveCallsWithDetails(tenantId);
    }

    /**
     * Get detail for a single call.
     */
    public Optional<Map<Object, Object>> getCallDetail(String callId) {
        return callTracker.getCallDetail(callId);
    }
}