package com.dalai.llama.tenant.service.provisioning.telecom.config;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * CoTURN (STUN/TURN server) configuration builder.
 * 
 * CoTURN handles:
 * - STUN for NAT discovery
 * - TURN relay for symmetric NAT traversal
 * - DTLS for secure WebRTC
 * - REST API for time-limited credentials
 */
@Slf4j
@Component
public class CoturnConfigBuilder {

    private final SecureRandom secureRandom = new SecureRandom();

    public String build(Tenant tenant, TelecomStackConfig config) {
        log.info("Building CoTURN config for tenant {}", tenant.getSlug());
        
        String authSecret = generateAuthSecret();
        
        return String.format("""
            # ==============================================
            # CoTURN Configuration
            # Tenant: %s
            # ==============================================
            
            # Listener IP addresses
            listening-ip=0.0.0.0
            
            # External/relay IP (public IP for NAT traversal)
            # Will be replaced with actual external IP at runtime
            external-ip=${EXTERNAL_IP}
            
            # Relay IP (internal IP for relaying)
            relay-ip=0.0.0.0
            
            # STUN/TURN ports
            listening-port=%d
            tls-listening-port=%d
            alt-listening-port=0
            alt-tls-listening-port=0
            
            # Relay port range (should not overlap with RTPEngine)
            min-port=49152
            max-port=65535
            
            # Realm for authentication
            realm=%s
            
            # Server name
            server-name=%s
            
            # Authentication
            # Use long-term credentials with REST API
            lt-cred-mech
            use-auth-secret
            static-auth-secret=%s
            
            # Alternatively, allow static users (for testing)
            # user=test:test123
            
            # Fingerprinting (STUN)
            fingerprint
            
            # Verbose logging
            verbose
            
            # Log file
            log-file=/var/log/coturn/turnserver.log
            
            # No loopback peers (security)
            no-loopback-peers
            
            # No multicast peers (security)
            no-multicast-peers
            
            # Mobility with ICE
            mobility
            
            # Don't allow peers on privileged ports
            no-cli
            
            # TLS/DTLS certificates
            cert=/etc/coturn/certs/turn.crt
            pkey=/etc/coturn/certs/turn.key
            # ca-file=/etc/coturn/certs/ca.crt
            
            # Cipher suite
            cipher-list="ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384"
            
            # EC curve
            ec-curve-name=prime256v1
            
            # DH file (optional, for older clients)
            # dh-file=/etc/coturn/dh2066.pem
            
            # No TLS v1.0/1.1 (security)
            no-tlsv1
            no-tlsv1_1
            
            # DTLS v1.2 only
            no-dtls
            
            # Enable STUN
            stun-only=false
            
            # Total relay quota (unlimited)
            total-quota=0
            
            # Per-user quota (unlimited)
            user-quota=0
            
            # Max BPS per session
            max-bps=0
            
            # Allocation lifetime (seconds)
            channel-lifetime=600
            permission-lifetime=300
            
            # Stale nonce lifetime
            stale-nonce=600
            
            # Redis for shared state (optional, for HA)
            # redis-statsdb="ip=127.0.0.1 dbname=0 password=turn connect_timeout=30"
            
            # Prometheus metrics (optional)
            # prometheus
            # prometheus-port=9641
            
            # Denied peer IP ranges (security)
            denied-peer-ip=0.0.0.0-0.255.255.255
            denied-peer-ip=10.0.0.0-10.255.255.255
            denied-peer-ip=100.64.0.0-100.127.255.255
            denied-peer-ip=127.0.0.0-127.255.255.255
            denied-peer-ip=169.254.0.0-169.254.255.255
            denied-peer-ip=172.16.0.0-172.31.255.255
            denied-peer-ip=192.0.0.0-192.0.0.255
            denied-peer-ip=192.0.2.0-192.0.2.255
            denied-peer-ip=192.88.99.0-192.88.99.255
            denied-peer-ip=192.168.0.0-192.168.255.255
            denied-peer-ip=198.18.0.0-198.19.255.255
            denied-peer-ip=198.51.100.0-198.51.100.255
            denied-peer-ip=203.0.113.0-203.0.113.255
            denied-peer-ip=240.0.0.0-255.255.255.255
            
            # Allow specific private ranges if needed for internal testing
            # allowed-peer-ip=10.0.0.0-10.255.255.255
            
            """,
            tenant.getSlug(),
            config.getTurnPort(),
            config.getTurnTlsPort(),
            config.getRealm(),
            "turn." + config.getRealm(),
            authSecret
        );
    }
    
    /**
     * Generate a secure auth secret for TURN REST API
     */
    private String generateAuthSecret() {
        byte[] secret = new byte[32];
        secureRandom.nextBytes(secret);
        return Base64.getEncoder().encodeToString(secret);
    }
    
    /**
     * Build startup script for CoTURN
     */
    public String buildStartupScript(TelecomStackConfig config) {
        return String.format("""
            #!/bin/bash
            # CoTURN startup script for tenant %s
            
            # Set external IP from environment or detect
            EXTERNAL_IP="${EXTERNAL_IP:-$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || hostname -I | awk '{print $1}')}"
            
            # Substitute external IP in config
            sed -i "s/\\${EXTERNAL_IP}/${EXTERNAL_IP}/g" /etc/coturn/turnserver.conf
            
            # Create log directory
            mkdir -p /var/log/coturn
            touch /var/log/coturn/turnserver.log
            
            # Start coturn
            exec /usr/bin/turnserver -c /etc/coturn/turnserver.conf
            """,
            config.getTenantSlug()
        );
    }
    
    /**
     * Generate time-limited TURN credentials (for WebRTC clients)
     * 
     * @param username The username (usually the SIP user)
     * @param secret The static auth secret from config
     * @param ttl Time-to-live in seconds
     * @return Credential pair (username:timestamp, password)
     */
    public TurnCredentials generateTurnCredentials(String username, String secret, int ttl) {
        long timestamp = System.currentTimeMillis() / 1000 + ttl;
        String turnUsername = timestamp + ":" + username;
        
        try {
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA1");
            javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA1");
            hmac.init(key);
            byte[] hash = hmac.doFinal(turnUsername.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String turnPassword = Base64.getEncoder().encodeToString(hash);
            
            return new TurnCredentials(turnUsername, turnPassword, ttl);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate TURN credentials", e);
        }
    }
    
    public record TurnCredentials(String username, String password, int ttl) {}
}
