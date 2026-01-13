package com.dalai.llama.pbx.core.kamailio;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KamailioClient {

    private final ObjectMapper objectMapper;

    // =====================================================
    //  OUTBOUND: ORIGINATE CALL (UAC INVITE)
    // =====================================================
    public void originate(String rpcUrl, String callId, String from, String to, String sdpOffer) {
        log.info("[Kamailio] Originate call {} from {} to {}", callId, from, to);

        Map<String,Object> params = new HashMap<>();
        params.put("callid", callId);
        params.put("from", from);
        params.put("to", to);
        params.put("body", sdpOffer);
        params.put("content_type", "application/sdp");

        send(rpcUrl, "uac.invite", params);
    }

    // =====================================================
    //  INBOUND: ANSWER CALL (200 OK)
    // =====================================================
    public void answerCall(String rpcUrl, String callId, String fromTag, String toTag, String sdpAnswer) {
        log.info("[Kamailio] Answer call {}", callId);

        Map<String,Object> params = new HashMap<>();
        params.put("callid", callId);
        params.put("from_tag", fromTag);
        params.put("to_tag", toTag);
        params.put("code", 200);
        params.put("reason", "OK");
        params.put("body", sdpAnswer);
        params.put("content_type", "application/sdp");

        send(rpcUrl, "dlg.send_reply", params);
    }

    // =====================================================
    //  INBOUND: REJECT CALL (486 / 603)
    // =====================================================
    public void reject(String rpcUrl, String callId, String fromTag, int code, String reason) {
        log.info("[Kamailio] Reject call {} with {} {}", callId, code, reason);

        Map<String,Object> params = new HashMap<>();
        params.put("callid", callId);
        params.put("from_tag", fromTag);
        params.put("code", code);
        params.put("reason", reason);

        send(rpcUrl, "dlg.send_reply", params);
    }

    // =====================================================
    //  TERMINATE CALL (Forced BYE)
    // =====================================================
    public void hangup(String rpcUrl, String callId) {
        log.info("[Kamailio] Hangup call {}", callId);

        Map<String,Object> params = new HashMap<>();
        params.put("callid", callId);

        send(rpcUrl, "dlg.end_dlg", params);
    }

    // =====================================================
    //  BLIND TRANSFER (REFER)
    // =====================================================
    public void transfer(String rpcUrl, String callId, String fromTag, String toTag, String destination) {
        log.info("[Kamailio] Transfer call {} → {}", callId, destination);

        Map<String,Object> params = new HashMap<>();
        params.put("callid", callId);
        params.put("from_tag", fromTag);
        params.put("to_tag", toTag);
        params.put("refer_to", destination);

        send(rpcUrl, "dlg.transfer", params);
    }

    // =====================================================
    //  ACTIVE CALLS LIST
    // =====================================================
    public Map<String,Object> listDialogs(String rpcUrl) {
        log.info("[Kamailio] Fetch active dialogs");

        return send(rpcUrl, "dlg.list", Map.of());
    }

    // =====================================================
    //  SUPERVISOR: LISTEN / BARGE-IN / WHISPER HOOKS
    // (actual mixing happens in RTPENGINE config)
    // =====================================================

    public void bargeIn(String rpcUrl, String supervisorCallId, String targetCallId, String supervisorContact) {
        log.info("[Kamailio] Barge-in supervisor {} into call {}", supervisorContact, targetCallId);

        Map<String,Object> params = new HashMap<>();
        params.put("supervisor_callid", supervisorCallId);
        params.put("target_callid", targetCallId);
        params.put("contact", supervisorContact);

        send(rpcUrl, "uac.bargein", params);  // Needs custom Kamailio route
    }

    public void listen(String rpcUrl, String supervisorCallId, String targetCallId, String supervisorContact) {
        log.info("[Kamailio] Listen supervisor {} to call {}", supervisorContact, targetCallId);

        Map<String,Object> params = new HashMap<>();
        params.put("supervisor_callid", supervisorCallId);
        params.put("target_callid", targetCallId);
        params.put("contact", supervisorContact);

        send(rpcUrl, "uac.listen", params); // Again, custom route in kamailio.cfg
    }

    public void whisper(String rpcUrl, String supervisorCallId, String targetCallId, String supervisorContact) {
        log.info("[Kamailio] Whisper supervisor {} to call {}", supervisorContact, targetCallId);

        Map<String,Object> params = new HashMap<>();
        params.put("supervisor_callid", supervisorCallId);
        params.put("target_callid", targetCallId);
        params.put("contact", supervisorContact);

        send(rpcUrl, "uac.whisper", params);  // Custom Kamailio + RTPENGINE logic
    }

    // =====================================================
    //  CORE JSONRPC SENDER
    // =====================================================
    private Map<String,Object> send(String rpcUrl, String method, Map<String,Object> params) {
        try {
            URL url = new URL(rpcUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            Map<String,Object> payload = new HashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("method", method);
            payload.put("params", params);
            payload.put("id", UUID.randomUUID().toString());

            String json = objectMapper.writeValueAsString(payload);
            log.debug("RPC → {}", json);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes());
            }

            int code = conn.getResponseCode();
            if (code != 200)
                throw new RuntimeException("Kamailio RPC HTTP " + code);

            Map resp = objectMapper.readValue(conn.getInputStream(), Map.class);
            log.debug("RPC ← {}", resp);

            if (resp.containsKey("error"))
                throw new RuntimeException("Kamailio RPC Error: " + resp.get("error"));

            return resp;

        } catch (Exception ex) {
            log.error("Kamailio RPC failed: {}", ex.getMessage(), ex);
            throw new RuntimeException(ex);
        }
    }
}
