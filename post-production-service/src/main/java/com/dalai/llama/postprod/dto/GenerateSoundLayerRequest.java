package com.dalai.llama.postprod.dto;

import com.dalai.llama.postprod.domain.SoundLayerKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/** Generate a sound for a shot from a description, e.g. "a single temple bell, long ring".
 *
 * @param durationSeconds how long to make it (music only; a sound effect is as long as the sound)
 * @param offsetMs        where it starts, from the start of the shot */
public record GenerateSoundLayerRequest(@NotNull UUID shotId, @NotNull SoundLayerKind kind, @NotBlank String prompt,
                                        @Min(1) @Max(120) Integer durationSeconds, @PositiveOrZero Integer offsetMs) {
}
