package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.MoodProfile;

public record Lighting(
        String keyLightNote,
        MoodProfile mood,
        /** The actual LIGHTING-kind ShotImage the DP-lighting plan produced (MinIO location) --
         * attached as a DP_LIGHTING ShotPromptReference so the video model sees the real
         * reference, not just a text note. Nullable: no lighting image degrades gracefully. */
        String dpLightingImageBucket,
        String dpLightingImageObjectKey
) {
}
