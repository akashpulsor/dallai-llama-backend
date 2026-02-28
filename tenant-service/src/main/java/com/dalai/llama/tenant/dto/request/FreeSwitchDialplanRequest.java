package com.dalai.llama.tenant.dto.request;

import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data
@Builder
public class FreeSwitchDialplanRequest {
    private UUID tenantId;
    private UUID subscriptionId;
    private String namespace;
    private String context;              // "tenant_acme"
    private String dialplanContent;      // Full generated dialplan string
    private String productCode;          // For PBX-Core to tag it (not interpret)
    private Boolean dedicatedInfrastructure;
}