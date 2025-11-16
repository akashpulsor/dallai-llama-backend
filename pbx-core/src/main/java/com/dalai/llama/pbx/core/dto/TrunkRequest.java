package com.dalai.llama.pbx.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TrunkRequest {
    @NotBlank private String name;
    @NotBlank private String sipUri;          // e.g., sip:carrier.example.com
    private String username;
    private String password;
    private String region;
    private boolean enabled = true;
}
