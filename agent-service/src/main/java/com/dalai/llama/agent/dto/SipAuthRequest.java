package com.dalai.llama.agent.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Data Transfer Object for Kamailio's SIP Digest Authentication Request (POST body).
 * Contains all necessary components to verify the client's digest 'response'.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SipAuthRequest {
    private String username;
    private String realm;
    private String tenantId;
    private String nonce;
    private String uri;
    private String response;
    private String method;
}