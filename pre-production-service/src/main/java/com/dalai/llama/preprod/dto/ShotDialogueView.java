package com.dalai.llama.preprod.dto;

import java.util.UUID;

/** Field names deliberately match post-production-service's own {@code PreProductionShotDetails}
 * contract exactly (same names, same order isn't required for JSON but kept for readability) --
 * that record's javadoc names this exact endpoint as the contract it expects. */
public record ShotDialogueView(
        UUID projectId,
        UUID scriptId,
        String shotRef,
        Integer shotNumber,
        String character,
        String dialogueScript,
        String sourceDialogueLanguage,
        String languageCode,
        String referenceAudioUrl
) {
}
