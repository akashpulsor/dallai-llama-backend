package com.dalai.llama.tenant.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TenantTelecomEndpoints {
    private String sipUdpUrl;
    private String sipTlsUrl;
    private String sipWssUrl;
    private String turnUrl;
    private String eslHost;
    private Integer eslPort;
    private String configSummary;
}