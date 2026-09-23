package com.dalai.llama.tenant.leadmanagement.controller;

import com.dalai.llama.tenant.leadmanagement.config.LeadManagementProperties;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** Cloudflare Email Worker -> backend transport landing pad. Phase 1 accepts and drops the
 * payload -- full conversation processing lands in a later phase (see the plan doc). This
 * class exists only to make the security boundary real: shared-secret header check, timestamp
 * replay guard, body-size cap, and a stable route the Worker can call.
 *
 * <p>Path lives under {@code /api/v1/internal/**}, which {@code SecurityConfig#internalFilterChain}
 * bypasses OAuth on; that is INTENTIONAL. The endpoint is reachable from outside the cluster
 * (the Worker runs on Cloudflare's edge, not inside k3s), so trusting the network is not an
 * option -- authenticity is decided ONLY by the shared secret + timestamp check below. */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/lead-management/email")
public class InternalLeadManagementEmailInboundController {

    private static final String HEADER_SECRET = "X-Webhook-Secret";
    private static final String HEADER_TIMESTAMP = "X-Webhook-Timestamp";

    private final LeadManagementProperties properties;

    @PostMapping(value = "/inbound", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> inbound(
            @RequestHeader(value = HEADER_SECRET, required = false) String presentedSecret,
            @RequestHeader(value = HEADER_TIMESTAMP, required = false) String presentedTimestamp,
            @RequestBody(required = false) String rawJsonBody,
            HttpServletRequest request) {

        String configuredSecret = properties.inboundWebhookSecret();
        if (configuredSecret == null || configuredSecret.isBlank()) {
            // Fail SAFE: if the secret was not injected (misconfigured env var), refuse to
            // accept traffic at all rather than default-accept. 503 tells the Worker to
            // back off and retry later; 401/403 would look like a permanent auth problem.
            log.error("Inbound email webhook called but no shared secret is configured -- refusing.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "webhook not configured"));
        }

        if (presentedSecret == null || !constantTimeEquals(presentedSecret, configuredSecret)) {
            // Never echo the presented secret in logs, even at DEBUG.
            log.warn("Inbound email webhook rejected: bad or missing X-Webhook-Secret from {}",
                    request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }

        if (!timestampWithinWindow(presentedTimestamp)) {
            log.warn("Inbound email webhook rejected: bad/expired X-Webhook-Timestamp={} from {}",
                    presentedTimestamp, request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "timestamp out of window"));
        }

        int bodyBytes = rawJsonBody == null ? 0 : rawJsonBody.getBytes(StandardCharsets.UTF_8).length;
        if (bodyBytes > properties.inboundWebhookMaxBodyBytes()) {
            // Request-body-too-large. Cloudflare's own limit is 25MB by default; ours is
            // configurable per-env. Rejecting here beats accepting a giant blob into JVM
            // heap for a use case we intentionally do not process in Phase 1.
            log.warn("Inbound email webhook rejected: body {} bytes > cap {} bytes",
                    bodyBytes, properties.inboundWebhookMaxBodyBytes());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "body too large"));
        }

        // Phase 1: acknowledge and drop. The actual reply/lead-thread processing lands in a
        // later phase; logging the size but NOT the body itself keeps sensitive content out
        // of logs while still confirming the transport works end-to-end.
        log.info("Inbound email webhook accepted (dropped, Phase 1): {} bytes", bodyBytes);
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }

    /** Length-then-XOR compare; MessageDigest.isEqual would work too but requires byte[]. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(ab, bb);
    }

    private boolean timestampWithinWindow(String presentedTimestamp) {
        if (presentedTimestamp == null || presentedTimestamp.isBlank()) return false;
        long parsed;
        try {
            parsed = Long.parseLong(presentedTimestamp.trim());
        } catch (NumberFormatException nfe) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        long skew = Math.abs(now - parsed);
        return skew <= properties.inboundWebhookTimestampToleranceSeconds();
    }
}
