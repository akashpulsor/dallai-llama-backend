package com.dalai.llama.pbx.core.kamailio;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kamailio JSONRPC Client
 * Controls Kamailio via JSONRPC for call operations
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KamailioClient {

    private final ObjectMapper objectMapper;

    @Value("${kamailio.jsonrpc.url:http://kamailio:8080/RPC}")
    private String kamailioJsonRpcUrl;

    @Value("${kamailio.timeout-ms:5000}")
    private int timeoutMs;

    /**
     * Answer incoming call
     * Sends SIP 200 OK with SDP answer
     */
    public void answerCall(String callId, String fromTag, String toTag, String sdpAnswer) {
        log.info("Answering call {} via Kamailio", callId);

        Map<String, Object> params = new HashMap<>();
        params.put("callid", callId);
        params.put("from_tag", fromTag);
        params.put("to_tag", toTag);
        params.put("code", 200);
        params.put("reason", "OK");
        params.put("body", sdpAnswer);
        params.put("content_type", "application/sdp");

        try {
            sendJsonRpcCommand("dlg.send_reply", params);
        } catch (Exception e) {
            log.error("Failed to answer call via Kamailio", e);
            throw new RuntimeException("Failed to answer call: " + e.getMessage());
        }
    }

    /**
     * Initiate outbound call
     * Sends SIP INVITE
     */
    public void initiateCall(String callId, String from, String to, String sdpOffer) {
        log.info("Initiating call {} from {} to {} via Kamailio", callId, from, to);

        Map<String, Object> params = new HashMap<>();
        params.put("callid", callId);
        params.put("from", from);
        params.put("to", to);
        params.put("body", sdpOffer);
        params.put("content_type", "application/sdp");

        try {
            sendJsonRpcCommand("uac.invite", params);
        } catch (Exception e) {
            log.error("Failed to initiate call via Kamailio", e);
            throw new RuntimeException("Failed to initiate call: " + e.getMessage());
        }
    }

    /**
     * Hangup/terminate call
     * Sends SIP BYE
     */
    public void hangupCall(String callId, String fromTag, String toTag) {
        log.info("Hanging up call {} via Kamailio", callId);

        Map<String, Object> params = new HashMap<>();
        params.put("callid", callId);
        params.put("from_tag", fromTag);
        params.put("to_tag", toTag);

        try {
            sendJsonRpcCommand("dlg.end_dlg", params);
        } catch (Exception e) {
            log.error("Failed to hangup call via Kamailio", e);
            throw new RuntimeException("Failed to hangup call: " + e.getMessage());
        }
    }

    /**
     * Reject incoming call
     * Sends SIP 486 Busy Here or 603 Decline
     */
    public void rejectCall(String callId, String fromTag, int code, String reason) {
        log.info("Rejecting call {} with code {}", callId, code);

        Map<String, Object> params = new HashMap<>();
        params.put("callid", callId);
        params.put("from_tag", fromTag);
        params.put("code", code);
        params.put("reason", reason);

        try {
            sendJsonRpcCommand("dlg.send_reply", params);
        } catch (Exception e) {
            log.error("Failed to reject call via Kamailio", e);
        }
    }

    /**
     * Transfer call (blind transfer)
     */
    public void transferCall(String callId, String fromTag, String toTag, String destination) {
        log.info("Transferring call {} to {}", callId, destination);

        Map<String, Object> params = new HashMap<>();
        params.put("callid", callId);
        params.put("from_tag", fromTag);
        params.put("to_tag", toTag);
        params.put("refer_to", destination);

        try {
            sendJsonRpcCommand("dlg.transfer", params);
        } catch (Exception e) {
            log.error("Failed to transfer call via Kamailio", e);
            throw new RuntimeException("Failed to transfer call: " + e.getMessage());
        }
    }

    /**
     * Get active dialogs (calls) from Kamailio
     */
    public Map<String, Object> getActiveDialogs() {
        try {
            return sendJsonRpcCommand("dlg.list", Map.of());
        } catch (Exception e) {
            log.error("Failed to get active dialogs", e);
            return Map.of();
        }
    }

    /**
     * Send JSONRPC command to Kamailio
     */
    private Map<String, Object> sendJsonRpcCommand(String method, Map<String, Object> params) 
            throws Exception {
        
        URL url = new URL(kamailioJsonRpcUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            // Build JSONRPC request
            Map<String, Object> request = new HashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("method", method);
            request.put("params", params);
            request.put("id", UUID.randomUUID().toString());

            String jsonRequest = objectMapper.writeValueAsString(request);
            log.debug("Sending to Kamailio: {}", jsonRequest);

            // Send request
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonRequest.getBytes());
                os.flush();
            }

            // Read response
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = objectMapper.readValue(
                    conn.getInputStream(), 
                    Map.class
                );
                
                log.debug("Received from Kamailio: {}", response);
                
                if (response.containsKey("error")) {
                    throw new RuntimeException("Kamailio error: " + response.get("error"));
                }
                
                return response;
            } else {
                throw new RuntimeException("Kamailio returned HTTP " + responseCode);
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Get Kamailio URL for tenant
     */
    public String getKamailioUrlForTenant(String tenantId) {
        // In multi-tenant setup, each tenant has own Kamailio instance
        return String.format("http://kamailio-%s.tenant-%s.svc.cluster.local:8080/RPC", 
            tenantId, tenantId);
    }

    /**
     * Check if Kamailio is reachable
     */
    public boolean isHealthy() {
        try {
            Map<String, Object> result = sendJsonRpcCommand("core.info", Map.of());
            return result.containsKey("result");
        } catch (Exception e) {
            log.warn("Kamailio health check failed", e);
            return false;
        }
    }
}
