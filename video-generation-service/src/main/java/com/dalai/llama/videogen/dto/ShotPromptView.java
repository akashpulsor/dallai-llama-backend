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
        String costCurrency,
        /** The clip length this prompt will actually be dispatched at, taken from its job rather
         * than from the shot plan.
         *
         * <p>They are not the same number once a shot's length is edited. The plan changes
         * immediately; the prepared prompt keeps the length it was built with until the shot is
         * prepared again, and it is the prompt that gets sent. A creator who lengthened a shot to
         * 8s had no way to tell from this page whether the change had reached the thing that would
         * be generated -- so the two are now shown side by side and the gap is named. */
        Integer durationSeconds,
        /** True when the compressed text is the one that will be dispatched.
         *
         * <p>Both prompts are on this view and only one of them gets sent, so without this a caller
         * editing "the prompt" has to guess which. Swapping a rephrased line into the wrong one
         * edits text nobody uses and looks exactly like success. */
        boolean compressionApplied
) {
}
