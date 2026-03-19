package com.dalai.llama.pbx.core.dto.response;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SIP credentials for Agent UI SIP.js registration.
 *
 * Returned by GET /api/v1/agents/me/sip-credentials.
 * Agent UI uses these to create SIP.UserAgent and register with Kamailio.
 *
 * The SIP password is auto-generated and rotated on each fetch.
 * PBX-Core updates the subscriber HA1/HA1B and returns the plaintext ONCE.
 * The password is never stored in plaintext — only HA1 hash in the subscriber table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SipCredentialsResponse {

    private String extension;          // "1001"
    private String sipDomain;          // "tenant-acme.dalaillama.in"
    private String sipUsername;        // "1001" (same as extension for agents)
    private String sipPassword;        // auto-generated, valid until next fetch
    private String sipWssUrl;          // "wss://sip.dalaillama.in:7443"
    private String sipUri;             // "sip:1001@tenant-acme.dalaillama.in"
    private String displayName;        // "Priya Sharma"
}