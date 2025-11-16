package com.dalai.llama.pbx.core.rtpengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RTPEngine NG Protocol Client
 * Communicates with RTPEngine for SDP offer/answer processing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RtpEngineClient {

    private final ObjectMapper objectMapper;

    @Value("${rtpengine.host:localhost}")
    private String rtpEngineHost;

    @Value("${rtpengine.port:22222}")
    private int rtpEnginePort;

    @Value("${rtpengine.timeout-ms:5000}")
    private int timeoutMs;

    // Cache active call sessions
    private final Map<String, RtpEngineSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * Process SDP offer from caller
     * Returns SDP answer for callee
     */
    public String processOffer(String callId, String fromTag, String toTag, String sdpOffer) throws IOException {
        log.info("Processing RTPEngine offer for call: {}", callId);

        Map<String, Object> command = new HashMap<>();
        command.put("command", "offer");
        command.put("call-id", callId);
        command.put("from-tag", fromTag);

        if (toTag != null && !toTag.isEmpty()) {
            command.put("to-tag", toTag);
        }

        command.put("sdp", sdpOffer);

        // Optional: codec preferences
        Map<String, Object> flags = new HashMap<>();
        flags.put("trust-address", true);
        flags.put("replace", new String[]{"origin", "session-connection"});
        command.put("flags", flags);

        Map<String, Object> response = sendCommand(command);

        if ("ok".equals(response.get("result"))) {
            String sdpAnswer = (String) response.get("sdp");

            // Cache session
            RtpEngineSession session = new RtpEngineSession();
            session.setCallId(callId);
            session.setFromTag(fromTag);
            session.setToTag(toTag);
            activeSessions.put(callId, session);

            return sdpAnswer;
        } else {
            throw new RuntimeException("RTPEngine offer failed: " + response.get("error-reason"));
        }
    }

    /**
     * Process SDP answer from callee
     * Updates RTPEngine with answer
     */
    public String processAnswer(String callId, String fromTag, String toTag, String sdpAnswer) throws IOException {
        log.info("Processing RTPEngine answer for call: {}", callId);

        Map<String, Object> command = new HashMap<>();
        command.put("command", "answer");
        command.put("call-id", callId);
        command.put("from-tag", fromTag);
        command.put("to-tag", toTag);
        command.put("sdp", sdpAnswer);

        Map<String, Object> flags = new HashMap<>();
        flags.put("trust-address", true);
        command.put("flags", flags);

        Map<String, Object> response = sendCommand(command);

        if ("ok".equals(response.get("result"))) {
            return (String) response.get("sdp");
        } else {
            throw new RuntimeException("RTPEngine answer failed: " + response.get("error-reason"));
        }
    }

    /**
     * Delete call from RTPEngine
     * Releases media resources
     */
    public void deleteCall(String callId) {
        log.info("Deleting call from RTPEngine: {}", callId);

        RtpEngineSession session = activeSessions.remove(callId);
        if (session == null) {
            log.warn("No RTPEngine session found for call: {}", callId);
            return;
        }

        try {
            Map<String, Object> command = new HashMap<>();
            command.put("command", "delete");
            command.put("call-id", callId);
            command.put("from-tag", session.getFromTag());

            if (session.getToTag() != null) {
                command.put("to-tag", session.getToTag());
            }

            sendCommand(command);
        } catch (Exception e) {
            log.error("Error deleting call from RTPEngine", e);
        }
    }

    /**
     * Start call recording
     */
    public void startRecording(String callId, String recordingPath) throws IOException {
        log.info("Starting recording for call: {} to path: {}", callId, recordingPath);

        RtpEngineSession session = activeSessions.get(callId);
        if (session == null) {
            throw new RuntimeException("No active session for call: " + callId);
        }

        Map<String, Object> command = new HashMap<>();
        command.put("command", "start recording");
        command.put("call-id", callId);
        command.put("from-tag", session.getFromTag());

        Map<String, Object> flags = new HashMap<>();
        flags.put("output-destination", recordingPath);
        command.put("flags", flags);

        sendCommand(command);
    }

    /**
     * Stop call recording
     */
    public void stopRecording(String callId) throws IOException {
        log.info("Stopping recording for call: {}", callId);

        RtpEngineSession session = activeSessions.get(callId);
        if (session == null) {
            return;
        }

        Map<String, Object> command = new HashMap<>();
        command.put("command", "stop recording");
        command.put("call-id", callId);
        command.put("from-tag", session.getFromTag());

        sendCommand(command);
    }

    /**
     * Query call statistics
     */
    public Map<String, Object> queryCallStats(String callId) throws IOException {
        RtpEngineSession session = activeSessions.get(callId);
        if (session == null) {
            return Map.of();
        }

        Map<String, Object> command = new HashMap<>();
        command.put("command", "query");
        command.put("call-id", callId);
        command.put("from-tag", session.getFromTag());

        return sendCommand(command);
    }

    /**
     * Send command to RTPEngine via UDP
     */
    private Map<String, Object> sendCommand(Map<String, Object> command) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);

            // Serialize command to JSON
            String jsonCommand = objectMapper.writeValueAsString(command);
            byte[] sendData = jsonCommand.getBytes();

            // Create and send packet
            InetAddress address = InetAddress.getByName(rtpEngineHost);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, rtpEnginePort);
            socket.send(sendPacket);

            log.debug("Sent to RTPEngine: {}", jsonCommand);

            // Receive response
            byte[] receiveData = new byte[65535];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            socket.receive(receivePacket);

            String jsonResponse = new String(receivePacket.getData(), 0, receivePacket.getLength());
            log.debug("Received from RTPEngine: {}", jsonResponse);

            // Parse response
            @SuppressWarnings("unchecked")
            Map<String, Object> response = objectMapper.readValue(jsonResponse, Map.class);

            return response;

        } catch (IOException e) {
            log.error("Error communicating with RTPEngine at {}:{}", rtpEngineHost, rtpEnginePort, e);
            throw e;
        }
    }

    /**
     * Get RTPEngine host for tenant
     * Format: rtpengine-{tenantId}.{namespace}.svc.cluster.local
     */
    public String getRtpEngineHostForTenant(String tenantId) {
        return String.format("rtpengine-%s.tenant-%s.svc.cluster.local", tenantId, tenantId);
    }

    /**
     * Session data for active calls
     */
    @lombok.Data
    private static class RtpEngineSession {
        private String callId;
        private String fromTag;
        private String toTag;
    }
}
