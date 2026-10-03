package com.dalai.llama.postprod.dto;

import java.util.UUID;

public record ShotView(
        UUID id,
        String shotRef,
        String videoUrl,
        int fps
) {
}