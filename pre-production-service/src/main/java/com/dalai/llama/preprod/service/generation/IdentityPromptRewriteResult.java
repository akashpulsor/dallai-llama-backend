package com.dalai.llama.preprod.service.generation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** See PRE_PROD_SHOT_IMAGE_IDENTITY_PROMPT_REWRITE -- a text model rewrites an already-assembled
 * identity-conditioned image prompt for natural, non-absolutist phrasing before it goes to the
 * image model, per the empirically-confirmed pattern in {@link ShotImagePromptBuilder}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityPromptRewriteResult(String prompt) {
}
