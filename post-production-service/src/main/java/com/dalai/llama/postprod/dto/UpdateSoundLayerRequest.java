package com.dalai.llama.postprod.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** Moves, levels or switches a sound layer. PATCH: only the fields sent change. */
public record UpdateSoundLayerRequest(@PositiveOrZero Integer offsetMs,
                                      @DecimalMin("-60") @DecimalMax("12") BigDecimal volumeDb,
                                      @PositiveOrZero Integer fadeInMs,
                                      @PositiveOrZero Integer fadeOutMs,
                                      Boolean included) {
}
