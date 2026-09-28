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
        String voiceRefObjectKey,
        /** What this person is CALLED. castId is a UUID, so until now the prompt could attach a
         * face and never say whose it was -- the model got a photograph and no name to tie it to
         * the line being spoken. */
        String name,
        /** The cast profile's own description, plus age and gender where set. Identity the model
         * should honour, not wardrobe or setting -- those come from the shot plan. */
        String description
) {
}
