package com.dalai.llama.videogen.dto.shotcontext;

public record Character(
        String castId,
        String faceRefBucket,
        String faceRefObjectKey,
        String wardrobeNote,
        String performanceDirection,
        /** Reference audio for this character's voice -- MinIO location of the cast profile's
         * voice sample, attached as a CHARACTER_VOICE ShotPromptReference so the video model /
         * downstream mux step can condition on the real voice, not just a text description.
         * Nullable: a character without a captured voice reference degrades gracefully to no
         * voice attachment. */
        String voiceRefBucket,
        String voiceRefObjectKey
) {
}
