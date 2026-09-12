package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.CastProfileType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** {@code projectId == null} creates a reusable library entry. Actor voice setup has two modes:
 * a human sample ({@code voiceRefBucket}+{@code voiceRefObjectKey}), which becomes HUMAN and is
 * cloned later by Prepare All Dialogues; or an AI/provider voice identity ({@code
 * clonedVoiceId}+{@code clonedVoiceProviderId}) selected from llm-gateway's built-in voice catalog.
 * {@code builtinVoiceId} remains accepted only for older clients and is mirrored into the new
 * provider identity fields as ElevenLabs-compatible legacy data. */
public record CreateCastProfileRequest(
        UUID projectId,
        CastProfileType profileType,
        @NotBlank String displayName,
        String faceRefBucket,
        String faceRefObjectKey,
        String description,
        Integer age,
        String gender,
        String voiceRefBucket,
        String voiceRefObjectKey,
        String builtinVoiceId,
        @Size(max = 128) String clonedVoiceId,
        @Size(max = 64) String clonedVoiceProviderId
) {
}