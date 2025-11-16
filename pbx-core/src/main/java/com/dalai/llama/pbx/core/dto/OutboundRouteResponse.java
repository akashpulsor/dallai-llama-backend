package com.dalai.llama.pbx.core.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OutboundRouteResponse {
    private String decision;     // deliver | reject
    private String trunkId;
    private String trunkUri;
    private String cli;
}
