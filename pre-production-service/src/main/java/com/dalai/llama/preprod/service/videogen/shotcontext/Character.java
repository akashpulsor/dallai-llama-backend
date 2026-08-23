package com.dalai.llama.preprod.service.videogen.shotcontext;

public record Character(
        String castId,
        String faceRefBucket,
        String faceRefObjectKey,
        String voiceRefBucket,
        String voiceRefObjectKey,
        String wardrobeNote,
        String performanceDirection
) {
}
