package com.dalai.llama.postprod.dto;


import java.util.UUID;

public record ShotFrameExtractionResult(
        UUID shotId,
        int frameCount
) {
}