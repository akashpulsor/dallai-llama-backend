package com.dalai.llama.tenant.service.impl;


import com.dalai.llama.tenant.domain.entity.TenantApp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * CoTURN Configuration Service
 *
 * Provides TURN/STUN credentials for WebRTC NAT traversal:
 * - Time-limited credentials via HMAC-SHA1
 * - Bandwidth limits per plan tier
 * - REST API for credential generation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoTurnConfigService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${dalaillama.coturn.host:turn.dalaillama.in}")
    private String turnHost;

    @Value("${dalaillama.coturn.port:3478}")
    private int turnPort;

    @Value("${dalaillama.coturn.tls-port:5349}")
    private int turnTlsPort;

    @Value("${dalaillama.coturn.secret:dalaillama-turn-secret}")
    private String turnSecret;

    @Value("${dalaillama.coturn.realm:dalaillama.in}")
    private String turnRealm;

    public void configureForSubscription(TenantApp app) {
        log.info("Configuring CoTURN for subscription {}", app.getSubscriptionId());

        String username = "tenant_" + app.getNamespace();
        long timestamp = System.currentTimeMillis() / 1000 + 86400; // 24h validity
        String turnUsername = timestamp + ":" + username;
        String turnPassword = generateTurnPassword(turnUsername);

        String key = "turn:cred:" + username;
        redisTemplate.opsForHash().put(key, "username", turnUsername);
        redisTemplate.opsForHash().put(key, "password", turnPassword);
        redisTemplate.opsForHash().put(key, "realm", turnRealm);
        redisTemplate.opsForHash().put(key, "max_bps", getBandwidthLimit(app.getPlanTier()));
        redisTemplate.expire(key, 25, TimeUnit.HOURS);

        // Set TURN URLs in app
        boolean dedicated = Boolean.TRUE.equals(app.getDedicatedInfrastructure());
        if (dedicated) {
            app.setTurnUrl(String.format("turn:turn.%s.dalaillama.in:%d", app.getNamespace(), turnPort));
        } else {
            app.setTurnUrl(String.format("turn:%s:%d", turnHost, turnPort));
        }

        log.info("CoTURN configured for {} - username: {}", app.getNamespace(), turnUsername);
    }

    private String generateTurnPassword(String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec keySpec = new SecretKeySpec(turnSecret.getBytes(), "HmacSHA1");
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(username.getBytes());
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            log.error("Failed to generate TURN password", e);
            throw new RuntimeException("TURN password generation failed", e);
        }
    }

    private String getBandwidthLimit(String planTier) {
        return switch (planTier) {
            case "ENTERPRISE" -> "0"; // Unlimited
            case "PROFESSIONAL" -> "10000000"; // 10 Mbps
            case "STANDARD" -> "5000000"; // 5 Mbps
            default -> "2000000"; // 2 Mbps
        };
    }

    /**
     * Get TURN credentials for WebRTC client (REST API)
     */
    public TurnCredentials getTurnCredentials(String tenantSlug) {
        String username = "tenant_" + tenantSlug;
        long timestamp = System.currentTimeMillis() / 1000 + 3600; // 1h validity
        String turnUsername = timestamp + ":" + username;
        String turnPassword = generateTurnPassword(turnUsername);

        return new TurnCredentials(
                turnUsername, turnPassword,
                String.format("turn:%s:%d?transport=udp", turnHost, turnPort),
                String.format("turns:%s:%d?transport=tcp", turnHost, turnTlsPort),
                String.format("stun:%s:%d", turnHost, turnPort),
                3600
        );
    }

    public record TurnCredentials(
            String username, String password,
            String turnUrl, String turnsUrl, String stunUrl,
            int ttl
    ) {}

    public void removeSubscriptionConfig(String tenantSlug) {
        redisTemplate.delete("turn:cred:tenant_" + tenantSlug);
        log.info("Removed CoTURN config for tenant {}", tenantSlug);
    }
}