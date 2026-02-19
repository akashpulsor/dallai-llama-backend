package com.dalai.llama.tenant.dto.request;

import lombok.Builder;

import java.util.UUID;

/**
 * Tenant's SIP trunk credentials (customer's access to YOUR platform)
 * For Kamailio subscriber table
 */
@Builder
public record TenantSipTrunkData(
        UUID id,
        String username,         // tenant_acme_trunk
        String passwordHash,     // HA1 hash for Kamailio
        String passwordPlain,    // Plain password (shown once)
        String domain,           // sip.dalaillama.in
        int port,                // 5060
        String realm,            // dalaillama.in
        String transport,        // UDP/TCP/TLS
        int maxConcurrentCalls
) {}
