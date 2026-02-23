package com.dalai.llama.tenant.service.impl;


import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * RTPEngine Configuration Service
 *
 * RTPEngine handles:
 * - Media relay (RTP/RTCP)
 * - Codec transcoding (G711 ↔ Opus)
 * - Recording media streams
 * - AI audio forking for real-time transcription
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RTPEngineConfigService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${dalaillama.rtpengine.recording-path:/var/spool/rtpengine}")
    private String recordingPath;

    public void configureForSubscription(TenantApp app, PlanEntitlementResponse e) {
        log.info("Configuring RTPEngine for subscription {}", app.getSubscriptionId());

        String key = "rtpengine:tenant:" + app.getTenant().getId();

        // Codec preferences based on tier
        String codecs = "ENTERPRISE".equals(app.getPlanTier())
                ? "opus,G722,PCMU,PCMA" : "opus,PCMU,PCMA";

        redisTemplate.opsForHash().put(key, "codecs", codecs);
        redisTemplate.opsForHash().put(key, "recording_enabled", String.valueOf(e.recordingEnabled()));
        redisTemplate.opsForHash().put(key, "recording_path", recordingPath + "/" + app.getNamespace());
        redisTemplate.opsForHash().put(key, "max_channels", String.valueOf(e.maxPstnChannels()));
        redisTemplate.opsForHash().put(key, "transcoding", "yes");

        // AI audio forking for real-time transcription
        redisTemplate.opsForHash().put(key, "ai_fork_enabled", String.valueOf(e.aiSttEnabled()));
        if (e.aiSttEnabled()) {
            redisTemplate.opsForHash().put(key, "ai_fork_target",
                    "udp:" + app.getNamespace() + "-ai-service:5555");
        }

        redisTemplate.expire(key, 30, TimeUnit.DAYS);
        log.info("RTPEngine configured for {} - codecs: {}, recording: {}, ai_fork: {}",
                app.getNamespace(), codecs, e.recordingEnabled(), e.aiSttEnabled());
    }

    /**
     * Get RTPEngine flags for call setup (called by Kamailio)
     */
    public String getRtpengineFlags(String tenantId, String direction) {
        String key = "rtpengine:tenant:" + tenantId;

        String codecs = (String) redisTemplate.opsForHash().get(key, "codecs");
        boolean recording = Boolean.parseBoolean(
                (String) redisTemplate.opsForHash().get(key, "recording_enabled"));
        boolean aiFork = Boolean.parseBoolean(
                (String) redisTemplate.opsForHash().get(key, "ai_fork_enabled"));

        StringBuilder flags = new StringBuilder("RTP/AVP replace-origin replace-session-connection ICE=remove ");

        if (codecs != null && !codecs.isEmpty()) {
            flags.append("codec-transcode-").append(codecs.split(",")[0]).append(" ");
        }
        if (recording) {
            flags.append("record-call=on ");
        }
        if (aiFork) {
            String target = (String) redisTemplate.opsForHash().get(key, "ai_fork_target");
            flags.append("SIPREC=").append(target).append(" ");
        }

        return flags.toString().trim();
    }

    public void removeSubscriptionConfig(String tenantId) {
        redisTemplate.delete("rtpengine:tenant:" + tenantId);
        log.info("Removed RTPEngine config for tenant {}", tenantId);
    }
}