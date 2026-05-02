package com.dalai.llama.pbx.core.controller.telecom;


import com.dalai.llama.pbx.core.redis.RtpEngineConfigRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * RTPEngine flag resolver — Kamailio calls this before rtpengine_manage().
 *
 * Kamailio determines callType from the dialplan context:
 *   - "bot"   → call routed to FreeSWITCH mod_audio_stream (AI products)
 *   - "agent" → call bridged to agent extension (BASIC_PBX or post-escalation)
 *
 * Fork-media logic:
 *   - callType=bot OR absent  → NO fork (bot uses mod_audio_stream WebSocket for STT)
 *   - callType=agent AND aiForkEnabled=true → fork-media to ai-service RTP receiver
 *
 * This ensures zero overlap: bot calls use WebSocket STT, agent calls use RTP fork STT.
 *
 * Security: IP-restricted via TrustedIpAuthorizationManager (chain 1 in SecurityConfig).
 * Kamailio runs on bare-metal outside the Kubernetes cluster.
 *
 * ──────────────────────────────────────────────────────────────────
 * Kamailio route block example (add to provisioned kamailio.cfg):
 *
 *   route[RTPENGINE_FLAGS] {
 *       # Determine call type from dialplan context
 *       $var(callType) = "agent";
 *       if ($ru =~ "mod_audio_stream" || $avp(routing_target) == "AI_BOT") {
 *           $var(callType) = "bot";
 *       }
 *
 *       # Fetch flags from PBX-Core
 *       http_client_query(
 *           "http://pbx-core:8080/internal/rtpengine/flags/$avp(tenant_id)?callType=$var(callType)",
 *           "$var(rtpflags_body)"
 *       );
 *       jansson_get("flags", "$var(rtpflags_body)", "$var(rtp_flags)");
 *
 *       # Apply to rtpengine_manage
 *       rtpengine_manage($var(rtp_flags));
 *   }
 * ──────────────────────────────────────────────────────────────────
 */
@Slf4j
@RestController
@RequestMapping("/rtpengine")
@RequiredArgsConstructor
public class RtpEngineController {

    private final RtpEngineConfigRedisService rtpEngineRedis;

    /**
     * GET /internal/rtpengine/flags/{tenantId}?callType=agent|bot
     *
     * Returns RTPEngine flags string for Kamailio to use in rtpengine_manage().
     *
     * Response:
     * {
     *   "flags": "ICE=remove RTP/AVP codec-mask=all codec-transcode=PCMU,PCMA fork-media=udp:ai-service:5555",
     *   "fork_active": true,
     *   "call_type": "agent"
     * }
     */
    @GetMapping("/flags/{tenantId}")
    public ResponseEntity<Map<String, Object>> getRtpEngineFlags(
            @PathVariable UUID tenantId,
            @RequestParam(required = false, defaultValue = "bot") String callType) {

        Optional<Map<Object, Object>> configOpt = rtpEngineRedis.get(tenantId);
        if (configOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "no_rtpengine_config",
                    "tenant_id", tenantId.toString()
            ));
        }

        Map<Object, Object> config = configOpt.get();

        // Base flags — always applied
        StringBuilder flags = new StringBuilder();
        flags.append("ICE=remove RTP/AVP");

        // Codec flags
        String codecs = String.valueOf(config.getOrDefault("codecs", ""));
        if (!codecs.isBlank()) {
            flags.append(" codec-mask=all codec-transcode=").append(codecs);
        }

        // Recording flags
        if ("true".equals(String.valueOf(config.getOrDefault("recording_enabled", "false")))) {
            String recordingPath = String.valueOf(config.getOrDefault("recording_path", ""));
            if (!recordingPath.isBlank()) {
                flags.append(" record-call=yes");
            }
        }

        // Fork-media: ONLY for agent calls when AI fork is enabled
        boolean forkActive = false;
        boolean aiForkEnabled = "true".equals(String.valueOf(config.getOrDefault("ai_fork_enabled", "false")));
        String aiForkTarget = String.valueOf(config.getOrDefault("ai_fork_target", ""));

        if ("agent".equalsIgnoreCase(callType) && aiForkEnabled && !aiForkTarget.isBlank()) {
            flags.append(" fork-media=").append(aiForkTarget);
            forkActive = true;
            log.debug("RTP fork ACTIVE for tenant={} callType={} target={}", tenantId, callType, aiForkTarget);
        } else {
            log.debug("RTP fork INACTIVE for tenant={} callType={} (enabled={}, target={})",
                    tenantId, callType, aiForkEnabled, aiForkTarget);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", tenantId.toString());
        result.put("call_type", callType);
        result.put("flags", flags.toString());
        result.put("fork_active", forkActive);
        result.put("ai_fork_enabled", aiForkEnabled);

        return ResponseEntity.ok(result);
    }
}
