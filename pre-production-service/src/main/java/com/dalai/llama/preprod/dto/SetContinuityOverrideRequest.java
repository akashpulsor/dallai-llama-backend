package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

/** The value a user chooses for one visual field of a step shot, over the continuity recommendation. */
public record SetContinuityOverrideRequest(@NotBlank String value) {
}
