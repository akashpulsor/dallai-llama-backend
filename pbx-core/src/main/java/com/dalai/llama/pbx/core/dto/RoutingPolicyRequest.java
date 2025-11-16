package com.dalai.llama.pbx.core.dto;

import java.util.List;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class RoutingPolicyRequest {
    @NotBlank private String strategy; // round_robin | longest_idle | priority-weighted | ai_hook
    private String teamId;
    private List<String> skills;
    private List<String> failover;     // e.g. ["team:Support L2","voicemail:vm_01"]
    private AIHook aiHook;

    @Data
    public static class AIHook {
        private String endpoint;
        private Integer timeoutMs;
    }
}
