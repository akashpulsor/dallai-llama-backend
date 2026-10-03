package com.dalai.llama.postprod.dto;


import java.util.UUID;

public record FrameExtractionRequestedEvent(
        UUID tenantId,
        UUID shotId
) {
}