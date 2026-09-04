package com.dalai.llama.preprod.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ShotDialogueBeatView(
        UUID id,
        Integer orderIndex,
        BigDecimal startSeconds,
        BigDecimal durationSeconds,
        String text,
        String characterKey
) {
}
