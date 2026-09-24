package com.dalai.llama.preprod.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

/** {@code briefText} is the locked idea's brief/concept text. In this v1 slice the caller passes
 * it directly rather than this service fetching it from creative-planning-service (that
 * cross-service read is a named follow-up, not yet built -- see PreProductionOrchestration notes).
 * {@code productCastProfileIds} are existing PRODUCT-typed {@code CastProfile}s (create one via
 * {@code POST /v1/cast-profiles} first) to ground the script in real products -- their name and
 * description are folded into the prompt so the LLM plans product-hero shots around what the
 * creator actually has, rather than inventing a generic product.
 * <p>
 * {@code dialogueLanguage} is a BCP-47 code (e.g. en-US, hi-IN, hi-Latn-IN) picked by the creator
 * on the generate form. When present it overrides the project's saved default AND becomes the
 * new saved default, so every downstream stage (screenplay, shot-list, dialogue-details) that
 * reads {@code ProjectConfig.dialogueLanguage} sees the same choice without the creator having to
 * set it in two places. Null means "use whatever's already on ProjectConfig, or en-US". */
public record GenerateScriptRequest(
        @NotBlank String briefText,
        Integer targetDurationSeconds,
        List<UUID> productCastProfileIds,
        String dialogueLanguage
) {
}
