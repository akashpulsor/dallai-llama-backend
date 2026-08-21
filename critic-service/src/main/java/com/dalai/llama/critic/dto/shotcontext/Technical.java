package com.dalai.llama.critic.dto.shotcontext;

import com.dalai.llama.critic.domain.AspectRatio;

public record Technical(
        Integer durationSeconds,
        AspectRatio aspectRatio,
        String targetProvider,
        String targetModel
) {
}
