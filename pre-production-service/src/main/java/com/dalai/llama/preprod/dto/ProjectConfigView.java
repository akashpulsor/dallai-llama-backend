package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.AspectRatio;

public record ProjectConfigView(
        AspectRatio aspectRatio,
        Integer targetDurationSeconds,
        Boolean preferMotionGraphics,
        String preferredVideoModel,
        String preferredVoiceModel,
        String preferredLipSyncModel,
        String dialogueLanguage
) {
}
