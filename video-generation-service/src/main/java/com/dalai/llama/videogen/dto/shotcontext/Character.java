package com.dalai.llama.videogen.dto.shotcontext;

public record Character(
        String castId,
        String faceRefBucket,
        String faceRefObjectKey,
        String wardrobeNote,
        String performanceDirection
) {
}
