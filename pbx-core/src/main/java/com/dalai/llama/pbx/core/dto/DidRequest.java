package com.dalai.llama.pbx.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DidRequest {
    @NotBlank private String number;
    @NotBlank private String entrypoint;   // e.g., team:support
    private String trunkId;                // optional bind to trunk
    private String status;                 // active|paused
}
