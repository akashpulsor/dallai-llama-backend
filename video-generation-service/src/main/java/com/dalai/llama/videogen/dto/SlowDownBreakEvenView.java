package com.dalai.llama.videogen.dto;

import java.math.BigDecimal;

/**
 * For one video model: slowing a shorter clip back to the shot's length saves money only while the
 * clip is under {@code ratio} of the shot's length (0.56 = 56%). Zero when it never saves; null when
 * the model has no per-second rate. Carries no rate, only the share.
 */
public record SlowDownBreakEvenView(String modelId, BigDecimal ratio) {
}
