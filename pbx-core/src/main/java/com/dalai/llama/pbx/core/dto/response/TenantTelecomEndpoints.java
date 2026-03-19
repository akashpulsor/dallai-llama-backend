package com.dalai.llama.pbx.core.dto.response;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Returned by POST /api/v1/provisioning/tenants/{tenantId}/kamailio.
 *
 * Tenant-service stores these values back on TenantApp entity:
 *   app.setSipUdpUrl(endpoints.getSipUdpUrl())
 *   app.setSipTlsUrl(endpoints.getSipTlsUrl())
 *   app.setFreeswitchEslHost(endpoints.getEslHost())
 *   app.setFreeswitchEslPort(endpoints.getEslPort())
 *
 * These are computed by PBX-Core based on deployment model (shared vs dedicated)
 * and the bare-metal telecom topology.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantTelecomEndpoints {

    private String sipUdpUrl;         // "sip:{externalIp}:5060;transport=udp"
    private String sipTlsUrl;         // "sip:{externalIp}:5061;transport=tls"
    private String sipWssUrl;         // "wss://{domain}:7443"
    private String turnUrl;           // "turn:{host}:3478"
    private String eslHost;           // FreeSWITCH ESL host (bare-metal IP)
    private Integer eslPort;          // FreeSWITCH ESL port (8021)
    private String configSummary;     // Human-readable summary of what was provisioned
}