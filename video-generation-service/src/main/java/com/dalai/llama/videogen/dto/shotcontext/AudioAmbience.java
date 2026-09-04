package com.dalai.llama.videogen.dto.shotcontext;

public record AudioAmbience(
        String ambientDescription,
        String musicMoodNote,
        /** Pre-generated background music track for this shot (MinIO location, from
         * pre-production's ShotBackgroundMusicController) -- attached as a BACKGROUND_MUSIC
         * ShotPromptReference for the downstream mux step to consume. Nullable. */
        String backgroundMusicBucket,
        String backgroundMusicObjectKey
) {
}
