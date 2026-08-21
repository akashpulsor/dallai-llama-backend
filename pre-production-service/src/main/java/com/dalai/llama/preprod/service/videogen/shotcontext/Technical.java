package com.dalai.llama.preprod.service.videogen.shotcontext;

import com.dalai.llama.preprod.domain.AspectRatio;

public record Technical(
        Integer durationSeconds,
        AspectRatio aspectRatio,
        String targetProvider,
        String targetModel
) {
}
