package com.dalai.llama.tenant.dto.request;

import lombok.Builder;

import java.util.UUID;

/**
 * SIP Endpoint for Kamailio subscriber table
 * Used for inbound DID registration
 */
@Builder
public record SipEndpointData(
        UUID id,
        String username,         // did_919876543210
        String passwordHash,     // HA1 hash for Kamailio
        String domain,           // sip.dalaillama.in
        String realm             // dalaillama.in
) {}
