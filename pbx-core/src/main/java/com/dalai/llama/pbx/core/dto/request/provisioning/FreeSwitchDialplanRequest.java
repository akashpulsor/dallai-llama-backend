package com.dalai.llama.pbx.core.dto.request.provisioning;



import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Received from tenant-service FreePBXConfigService.configureForSubscription().
 *
 * Tenant-service has already generated the complete FreeSWITCH dialplan XML
 * based on product code + entitlements. PBX-Core just stores it.
 * FreeSWITCH mod_xml_curl reads it at call time via /internal/freeswitch/dialplan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreeSwitchDialplanRequest {

    private UUID tenantId;
    private UUID subscriptionId;
    private String namespace;
    private String context;              // "tenant_{namespace}" — mod_xml_curl query key
    private String dialplanContent;      // Full FreeSWITCH dialplan XML
    private String productCode;
    private Boolean dedicatedInfrastructure;
}