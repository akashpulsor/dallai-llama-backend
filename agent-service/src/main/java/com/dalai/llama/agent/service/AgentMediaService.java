package com.dalai.llama.agent.service;

import com.dalai.llama.agent.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMediaService {

    private final RestTemplate restTemplate;

    @Value("${MEDIA_SERVER_URL}")
    private String mediaServerBaseUrl; // provisioned by PBX-Core

    private String url(String path) {
        return mediaServerBaseUrl + path;
    }

    private HttpHeaders headers(String tenantId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Tenant-ID", tenantId);   // good for auth/tenant isolation
        return h;
    }

    private void post(String url, Object body, String tenantId) {
        try {
            HttpEntity<Object> entity = new HttpEntity<>(body, headers(tenantId));
            restTemplate.postForEntity(url, entity, Void.class);
        } catch (Exception e) {
            log.error("MediaServer POST failed → {} | {}", url, e.getMessage());
            throw new RuntimeException("MediaServer request failed", e);
        }
    }

    private <T> T postFor(String url, Object body, String tenantId, Class<T> clazz) {
        try {
            HttpEntity<Object> entity = new HttpEntity<>(body, headers(tenantId));
            ResponseEntity<T> resp = restTemplate.postForEntity(url, entity, clazz);
            return resp.getBody();
        } catch (Exception e) {
            log.error("MediaServer POST_FOR failed → {} | {}", url, e.getMessage());
            throw new RuntimeException("MediaServer request failed", e);
        }
    }

    // ----------------------------------------------------
    //                     BARGE
    // ----------------------------------------------------
    public void barge(String tenantId, Long supervisorId, String callId, BargeRequest req) {
        String endpoint = url("/api/v1/media/tenants/" + tenantId + "/calls/" + callId + "/barge");
        log.info("Calling MediaServer BARGE: {}", endpoint);
        post(endpoint, req, tenantId);
    }

    // ----------------------------------------------------
    //                     WHISPER
    // ----------------------------------------------------
    public void whisper(String tenantId, Long supervisorId, String callId, WhisperRequest req) {
        String endpoint = url("/api/v1/media/tenants/" + tenantId + "/calls/" + callId + "/whisper");
        log.info("Calling MediaServer WHISPER: {}", endpoint);
        post(endpoint, req, tenantId);
    }

    // ----------------------------------------------------
    //                     MONITOR
    // ----------------------------------------------------
    public void monitor(String tenantId, Long supervisorId, String callId, MonitorRequest req) {
        String endpoint = url("/api/v1/media/tenants/" + tenantId + "/calls/" + callId + "/monitor");
        log.info("Calling MediaServer MONITOR: {}", endpoint);
        post(endpoint, req, tenantId);
    }

    // ----------------------------------------------------
    //                 START CONFERENCE
    // ----------------------------------------------------
    public ConferenceResponse startConference(String tenantId, Long agentId, ConferenceRequest req) {
        String endpoint = url("/api/v1/media/tenants/" + tenantId + "/conferences");
        log.info("Calling MediaServer START CONFERENCE: {}", endpoint);
        return postFor(endpoint, req, tenantId, ConferenceResponse.class);
    }

    // ----------------------------------------------------
    //                   JOIN CONFERENCE
    // ----------------------------------------------------
    public void joinConference(String tenantId, Long agentId, String roomId, JoinConferenceRequest req) {
        String endpoint = url("/api/v1/media/tenants/" + tenantId + "/conferences/" + roomId + "/join");
        log.info("Calling MediaServer JOIN CONFERENCE: {}", endpoint);
        post(endpoint, req, tenantId);
    }

    // ----------------------------------------------------
    //                   RECORDING
    // ----------------------------------------------------
    public void startRecording(String tenantId, Long agentId, String callId) {
        String endpoint = url("/api/v1/media/tenants/" + tenantId + "/calls/" + callId + "/recording/start");
        log.info("Calling MediaServer START RECORDING: {}", endpoint);
        post(endpoint, null, tenantId);
    }

    public void stopRecording(String tenantId, Long agentId, String callId) {
        String endpoint = url("/api/v1/media/tenants/" + tenantId + "/calls/" + callId + "/recording/stop");
        log.info("Calling MediaServer STOP RECORDING: {}", endpoint);
        post(endpoint, null, tenantId);
    }
}
