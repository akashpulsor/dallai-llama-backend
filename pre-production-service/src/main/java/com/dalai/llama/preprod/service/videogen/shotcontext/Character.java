package com.dalai.llama.preprod.service.videogen.shotcontext;

public record Character(
        String castId,
        String faceRefBucket,
        String faceRefObjectKey,
        String wardrobeNote,
        String performanceDirection
) {
}
