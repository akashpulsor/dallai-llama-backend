package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.AspectRatio;

public record ProjectConfigView(
        AspectRatio aspectRatio,
        Integer targetDurationSeconds,
        Boolean preferMotionGraphics,
        String preferredVideoModel,
        String preferredVoiceModel,
        String preferredLipSyncModel,
        String dialogueLanguage,
        /** Feature-flag gates for the video-workspace UI's optional enrichments (see
         * ProjectConfig entity javadoc for per-flag intent). All default to a sensible on/off
         * that matches migration V48; UI honors them before showing the corresponding UX. */
        Boolean recommenderEnabled,
        Boolean costPreviewEnabled,
        Boolean autoCloneAudioPromptEnabled,
        Boolean priceDeltaModalEnabled
) {
}
