package com.dalai.llama.pbx.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class TenantProvisioningRequest {
    @NotBlank private String authRealm;
    private List<String> dispatcherTargets;
    private String turnRealm;
    private String turnPolicy;
    private String turnPortRange;
    private String wssUrl;
    private List<String> stunUrls;
    private List<String> turnUrls;

    private String clientName;

    private AIFeatures aiFeatures;
    @Data
    public static class AIFeatures {
        private boolean routing;
        private boolean transcription;
        private boolean noiseCancellation;
    }
}
