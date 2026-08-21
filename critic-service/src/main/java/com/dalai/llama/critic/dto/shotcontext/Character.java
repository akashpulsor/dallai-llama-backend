package com.dalai.llama.critic.dto.shotcontext;

public record Character(
        String castId,
        String faceRefBucket,
        String faceRefObjectKey,
        String wardrobeNote,
        String performanceDirection
) {
}
