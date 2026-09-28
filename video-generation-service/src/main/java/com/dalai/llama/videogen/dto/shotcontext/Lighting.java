package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.MoodProfile;

public record Lighting(
        String keyLightNote,
        MoodProfile mood,
        /** The lighting plan's actual fixtures, as planned by the DP critic.
         *
         * <p>A thirteen-field lighting plan used to reach the prompt as ONE field --
         * cinematicIntent, via keyLightNote -- plus the shot's mood enum. So a shot with a
         * designed key, fill, rim, negative fill and diffusion was described to the video model as
         * "soft", and every fixture the plan named was dropped on the floor. The camera plan's
         * fifty-one fields all survive; lighting's did not, which is why prompts read as
         * thoroughly shot and barely lit. */
        String keyLightGear,
        String fillLightGear,
        String rimLightGear,
        String negFillGear,
        String diffuserGear,
        /** The actual LIGHTING-kind ShotImage the DP-lighting plan produced (MinIO location) --
         * attached as a DP_LIGHTING ShotPromptReference so the video model sees the real
         * reference, not just a text note. Nullable: no lighting image degrades gracefully. */
        String dpLightingImageBucket,
        String dpLightingImageObjectKey
) {
}
