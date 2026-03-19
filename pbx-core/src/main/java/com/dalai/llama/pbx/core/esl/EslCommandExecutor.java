package com.dalai.llama.pbx.core.esl;


import com.dalai.llama.pbx.core.exception.EslConnectionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Typed ESL command methods — the single control plane for FreeSWITCH.
 *
 * ALL call control goes through here:
 *   - CallControlService calls these for Agent UI actions
 *   - SupervisorService calls eavesdrop methods
 *   - DialerEngine calls originate for outbound campaigns
 *   - voice-brain calls interrupt/transfer via PBX-Core HTTP → here
 *
 * voice-brain does NOT talk to FreeSWITCH ESL directly.
 * When voice-brain needs to control a call, it sends HTTP to PBX-Core,
 * which translates to ESL commands here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EslCommandExecutor {

    private final EslConnectionManager connectionManager;

    // ═══════════════════════════════════════════════════════════
    // Call origination
    // ═══════════════════════════════════════════════════════════

    /**
     * Originate a new call.
     * Returns Job-UUID (bgapi — asynchronous origination).
     *
     * Example origination string:
     *   originate {origination_caller_id_number=+1555123}sofia/internal/1001@tenant-acme.dalaillama.in &park
     */
    public String originate(String originateString) {
        return bgApi("originate", originateString);
    }

    // ═══════════════════════════════════════════════════════════
    // Call control (per-call UUID commands)
    // ═══════════════════════════════════════════════════════════

    public String answer(String uuid) {
        return api("uuid_answer", uuid);
    }

    public String hold(String uuid) {
        return api("uuid_hold", "toggle " + uuid);
    }

    /**
     * Transfer call to a new destination.
     * destination format: "1001 XML tenant_acme" or "sofia/gateway/trunk1/+1555999"
     */
    public String transfer(String uuid, String destination) {
        return api("uuid_transfer", uuid + " " + destination);
    }

    public String hangup(String uuid) {
        return api("uuid_kill", uuid);
    }

    public String hangup(String uuid, String cause) {
        return api("uuid_kill", uuid + " " + cause);
    }

    /**
     * Send DTMF tones into a call.
     * digits: "1234" or "1@500" (digit@duration_ms)
     */
    public String sendDtmf(String uuid, String digits) {
        return api("uuid_send_dtmf", uuid + " " + digits);
    }

    /**
     * Interrupt TTS playback — used by voice-brain for barge-in.
     * When caller speaks during TTS, voice-brain detects voice activity
     * and calls PBX-Core POST /api/v1/calls/{callId}/interrupt
     * which calls this to stop the current playback.
     */
    public String interrupt(String uuid) {
        return api("uuid_break", uuid);
    }

    /**
     * Bridge two call legs together.
     */
    public String bridge(String uuid, String otherUuid) {
        return api("uuid_bridge", uuid + " " + otherUuid);
    }

    /**
     * Park a call (put on hold without MOH — used for queue waiting).
     */
    public String park(String uuid) {
        return api("uuid_park", uuid);
    }

    // ═══════════════════════════════════════════════════════════
    // Recording
    // ═══════════════════════════════════════════════════════════

    /**
     * Start recording a call to a file.
     */
    public String startRecording(String uuid, String filePath) {
        return api("uuid_record", uuid + " start " + filePath);
    }

    public String stopRecording(String uuid, String filePath) {
        return api("uuid_record", uuid + " stop " + filePath);
    }

    // ═══════════════════════════════════════════════════════════
    // Supervisor — eavesdrop modes
    // ═══════════════════════════════════════════════════════════

    /**
     * Listen mode — supervisor hears both legs, neither party hears supervisor.
     * Originates a new call leg for the supervisor that eavesdrops on the target.
     */
    public String listen(String supervisorUuid, String targetUuid) {
        return api("uuid_transfer",
                supervisorUuid + " 'eavesdrop::" + targetUuid + "' inline");
    }

    /**
     * Whisper mode — supervisor can speak to agent only, caller doesn't hear.
     * Uses eavesdrop with whisper flag.
     */
    public String whisper(String supervisorUuid, String targetUuid) {
        return api("uuid_setvar", targetUuid + " eavesdrop_whisper_aleg true");
    }

    /**
     * Barge mode — supervisor joins the call, all three parties can hear each other.
     * Creates a 3-way conference.
     */
    public String barge(String supervisorUuid, String targetUuid) {
        return api("uuid_transfer",
                supervisorUuid + " 'eavesdrop::" + targetUuid + "' inline");
    }

    // ═══════════════════════════════════════════════════════════
    // Query
    // ═══════════════════════════════════════════════════════════

    /**
     * List all active calls — returns FreeSWITCH "show calls" output.
     */
    public String showCalls() {
        return api("show", "calls");
    }

    /**
     * List active channels — returns FreeSWITCH "show channels" output.
     */
    public String showChannels() {
        return api("show", "channels");
    }

    /**
     * Get a specific channel variable.
     */
    public String getVariable(String uuid, String varName) {
        return api("uuid_getvar", uuid + " " + varName);
    }

    // ═══════════════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════════════

    private String api(String command, String args) {
        try {
            String response = connectionManager.getClient().sendApi(command, args);
            log.debug("ESL api {} {} → {}", command, args,
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
            return response;
        } catch (IOException e) {
            log.error("ESL command failed: {} {} — {}", command, args, e.getMessage());
            connectionManager.onDisconnect();
            throw new EslConnectionException("ESL command failed: " + command, e);
        }
    }

    private String bgApi(String command, String args) {
        try {
            String response = connectionManager.getClient().sendBgApi(command, args);
            log.debug("ESL bgapi {} → {}", command, response);
            return response;
        } catch (IOException e) {
            log.error("ESL bgapi failed: {} — {}", command, e.getMessage());
            connectionManager.onDisconnect();
            throw new EslConnectionException("ESL bgapi failed: " + command, e);
        }
    }
}