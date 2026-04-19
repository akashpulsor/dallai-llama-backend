package com.dalai.llama.pbx.core.client;


import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client to ai-service — RTP session registration for forked media.
 *
 * Called by EslEventListener on CHANNEL_BRIDGE (register) and CHANNEL_HANGUP (deregister).
 *
 * When RTPEngine forks media to ai-service, ai-service needs to know which
 * tenant/call the incoming RTP packets belong to. SSRC (Synchronization Source)
 * from the RTP headers is the correlation key.
 *
 * Endpoints called:
 *   POST   /api/rtp-sessions              → register SSRC pair for a call
 *   DELETE /api/rtp-sessions/{callId}      → deregister on hangup
 *
 * FAIL-OPEN: If ai-service is unreachable, the call continues — RTP fork
 * still happens at the RTPEngine level, ai-service just won't correlate it.
 * Transcription will fall back to silence until ai-service reconnects.
 */
@Slf4j
public class AiServiceClient {

    private final WebClient client;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public AiServiceClient(WebClient client) {
        this.client = client;
    }

    /**
     * Register RTP session — ai-service maps SSRC→tenant/call for forked media.
     *
     * Called on CHANNEL_BRIDGE when tenant has aiForkEnabled=true.
     *
     * @param callId      FreeSWITCH UUID
     * @param tenantId    Tenant UUID
     * @param ssrcCaller  Remote SSRC (caller leg)
     * @param ssrcAgent   Local SSRC (agent leg)
     */
    public void registerRtpSession(String callId, UUID tenantId, String ssrcCaller, String ssrcAgent) {
        try {
            client.post()
                    .uri("/api/rtp-sessions")
                    .bodyValue(Map.of(
                            "call_id", callId,
                            "tenant_id", tenantId.toString(),
                            "ssrc_caller", ssrcCaller != null ? ssrcCaller : "",
                            "ssrc_agent", ssrcAgent != null ? ssrcAgent : ""
                    ))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
            log.info("RTP session registered: callId={} tenant={} ssrc_caller={} ssrc_agent={}",
                    callId, tenantId, ssrcCaller, ssrcAgent);
        } catch (Exception e) {
            // FAIL-OPEN: ai-service down → fork still happens, just no correlation
            log.warn("Failed to register RTP session with ai-service for callId={}: {}",
                    callId, e.getMessage());
        }
    }

    /**
     * Deregister RTP session — ai-service cleans up SSRC mapping.
     *
     * Called on CHANNEL_HANGUP for calls that had an active RTP fork.
     */
    public void deregisterRtpSession(String callId) {
        try {
            client.delete()
                    .uri("/api/rtp-sessions/{callId}", callId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
            log.debug("RTP session deregistered: callId={}", callId);
        } catch (Exception e) {
            // Deregister failure is non-critical — ai-service will TTL-expire stale sessions
            log.warn("Failed to deregister RTP session for callId={}: {}", callId, e.getMessage());
        }
    }

    /**
     * Health check — verify ai-service is reachable.
     */
    public boolean isHealthy() {
        try {
            client.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
