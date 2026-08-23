package com.dalai.llama.creativeplanning.dto;

import jakarta.validation.constraints.NotBlank;

/** The chosen option, echoed back exactly as the client received it from generate-options --
 * since options aren't persisted server-side, locking one means "here's the option I picked,"
 * not "here's an id, look it up." */
public record LockIdeaOptionRequest(
        @NotBlank String title,
        String concept,
        String targetAudience,
        String campaignAngle,
        String keyMessage,
        String tone
) {
}
