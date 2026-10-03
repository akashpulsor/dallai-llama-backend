package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.Size;

/** Optional extra instruction for a revision, on top of the feedback already recorded on the treatment. */
public record ReviseCreativeDirectionRequest(@Size(max = 4000) String note) {
}
