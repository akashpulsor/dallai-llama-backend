package com.dalai.llama.videogen.dto;

import java.util.List;
import java.util.UUID;

public record ShotPromptView(
        UUID promptId,
        UUID jobId,
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
        List<String> referenceImageUrls
) {
}
