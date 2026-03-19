package com.dalai.llama.pbx.core.dto.response;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Returned by POST /api/v1/provisioning/tenants/{tenantId}/turn.
 *
 * Tenant-service stores turnUrl back on TenantApp:
 *   app.setTurnUrl(creds.getTurnUrl())
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnCredentialsResponse {

    private String username;
    private String password;
    private String turnUrl;           // "turn:{host}:3478"
    private String turnsUrl;          // "turns:{host}:5349"
    private String stunUrl;           // "stun:{host}:3478"
    private Integer ttl;
}