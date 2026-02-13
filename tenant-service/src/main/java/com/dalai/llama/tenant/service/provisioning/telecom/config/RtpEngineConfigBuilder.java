package com.dalai.llama.tenant.service.provisioning.telecom.config;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RTPEngine configuration builder.
 * 
 * RTPEngine handles:
 * - RTP/RTCP media proxying
 * - DTLS-SRTP for WebRTC
 * - Codec transcoding (OPUS <-> PCMA/PCMU)
 * - ICE/STUN/TURN for NAT traversal
 * - Call recording tap
 */
@Slf4j
@Component
public class RtpEngineConfigBuilder {

    public String build(Tenant tenant, TelecomStackConfig config) {
        log.info("Building RTPEngine config for tenant {}", tenant.getSlug());
        
        return String.format("""
            # ==============================================
            # RTPEngine Configuration
            # Tenant: %s
            # ==============================================
            
            # Kernel table ID (0 = disable kernel module)
            table = 0
            
            # Control socket for Kamailio
            listen-ng = 0.0.0.0:22222
            
            # CLI control socket (for debugging)
            listen-cli = 127.0.0.1:9900
            
            # Interfaces
            # Local interface for internal RTP
            interface = internal/127.0.0.1
            
            # External interface for public RTP
            interface = external/${EXTERNAL_IP}
            
            # Media port range
            port-min = %d
            port-max = %d
            
            # Timeouts
            timeout = 60
            silent-timeout = 600
            final-timeout = 7200
            offer-timeout = 60
            
            # TOS/DSCP for QoS
            tos = 184
            
            # Logging
            log-level = 5
            log-stderr = true
            log-facility = daemon
            
            # Recording
            # Enable recording via copy mode
            recording-dir = %s
            recording-method = pcap
            recording-format = eth
            
            # DTLS for WebRTC
            dtls-passive = true
            
            # ICE handling
            # ice-lite mode for server-side ICE
            ice-lite = true
            
            # Codec options
            # Allow transcoding between OPUS and legacy codecs
            codec-accept = *
            
            # STUN server for ICE candidates
            # stun-server = stun.l.google.com:19302
            
            # Thread pool
            num-threads = 4
            
            # Homer/HEP for SIP tracing (optional)
            # homer = 10.0.0.1:9060
            # homer-protocol = udp
            # homer-id = %s
            
            # Redis for distributed setups (optional)
            # redis = redis://localhost:6379/0
            # subscribe-keyspace = 1
            
            # Delete delay (ms) before removing call
            delete-delay = 30
            
            # No fallback for failed transcoding
            no-fallback = false
            
            # Strict SIP source mode
            strict-source = false
            
            # Media timeout actions
            media-address-type = any
            
            """,
            tenant.getSlug(),
            config.getRtpPortRangeStart(),
            config.getRtpPortRangeEnd(),
            config.getRecordingPath(),
            config.getTenantId()
        );
    }
    
    /**
     * Build RTPEngine startup script
     */
    public String buildStartupScript(TelecomStackConfig config) {
        return String.format("""
            #!/bin/bash
            # RTPEngine startup script for tenant %s
            
            # Set external IP from environment or detect
            EXTERNAL_IP="${EXTERNAL_IP:-$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || hostname -I | awk '{print $1}')}"
            
            # Substitute external IP in config
            sed -i "s/\\${EXTERNAL_IP}/${EXTERNAL_IP}/g" /etc/rtpengine/rtpengine.conf
            
            # Create recording directory
            mkdir -p %s
            chmod 755 %s
            
            # Start RTPEngine
            exec /usr/bin/rtpengine --config-file=/etc/rtpengine/rtpengine.conf --foreground
            """,
            config.getTenantSlug(),
            config.getRecordingPath(),
            config.getRecordingPath()
        );
    }
}
