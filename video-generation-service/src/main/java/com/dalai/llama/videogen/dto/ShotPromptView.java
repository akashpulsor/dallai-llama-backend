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
        String jobStatus
) {
}
