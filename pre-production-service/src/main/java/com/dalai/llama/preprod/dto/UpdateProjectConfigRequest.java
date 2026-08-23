package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.AspectRatio;

/** Every field optional/nullable -- callers set only what they're changing, same partial-update
 * convention used elsewhere in this service (e.g. CastAssignment). */
public record UpdateProjectConfigRequest(
        AspectRatio aspectRatio,
        Integer targetDurationSeconds,
        Boolean preferMotionGraphics,
        String dialogueLanguage
) {
}
