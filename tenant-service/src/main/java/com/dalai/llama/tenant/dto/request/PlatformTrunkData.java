package com.dalai.llama.tenant.dto.request;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * Platform SIP trunk (Epsilon) for outbound calls
 * For FreePBX trunk configuration
 */
@Builder
public record PlatformTrunkData(
        UUID id,
        String provider,         // EPSILON
        String server,           // sip.epsilon.in
        int port,                // 5060
        String transport,        // UDP
        List<String> codecs      // G711, G729, OPUS
) {}
