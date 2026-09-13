package com.dalai.llama.product.dto.creatorvideo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.util.UUID;

@Getter
public class CreatorVideoSubscribeRequest {

    @NotNull
    private UUID tenantId;

    /** e.g. CREATOR_VIDEO_PRO_MONTHLY / _QUARTERLY / _YEARLY. */
    @NotBlank
    private String planCode;
}
