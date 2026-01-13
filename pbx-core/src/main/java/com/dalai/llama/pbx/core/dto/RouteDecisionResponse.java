package com.dalai.llama.pbx.core.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RouteDecisionResponse {
    private String decision;           // deliver | fallback | reject | ivr
    private String strategyApplied;
    private int ttlMs;
    private List<Target> targets;
    private String ivrFlow;
    @Data
    @Builder
    public static class Target {
        private String type;           // agent | voicemail | trunk
        private String agentId;        // when type=agent
        private String contact;        // contact URI
    }
}
