package com.dalai.llama.videogen.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ShotPromptView(
        UUID promptId,
        UUID jobId,
        UUID shotId,
        String promptOriginal,
        String promptCompressed,
        String negativePrompt,
        FeatureFlags featureFlags,
        boolean shipped,
        UUID parentPromptId,
        List<FoleyCueView> foleyCues,
        /** The associated video_gen_job's approval state -- PENDING/APPROVED/REJECTED. Lets a
         * caller listing a shot's prompt history see, per version, whether it was approved
         * without a second call per prompt. */
        String approvalStatus,
        String jobStatus,
        /** Signed URLs for this prompt's saved character-face/product-hero references, in the
         * same order sent to the model -- what the video was actually conditioned on, not just
         * the text. Empty when the shot had none. */
        List<String> referenceImageUrls,
        /** Every saved reference for this prompt, images AND audio, each tagged with its kind and
         * slot. {@link #referenceImageUrls} above stays the model-facing list (image kinds only,
         * in slot order) because that is what fills the provider's reference-image slots; this is
         * the human-facing one -- it lets the UI show thumbnails of the exact frames, the
         * character voice sample and the background music track that went into this prompt,
         * rather than the creator having to trust that the right assets were picked up. */
        List<PromptReferenceView> references,
        /** Null when the shot pinned its own model (no recommendation ran). No reasoning field --
         * that's only ever ephemeral narration from the recommendation call, never persisted. */
        String recommendedModelId,
        BigDecimal estimatedCost,
        String costCurrency
) {
}
