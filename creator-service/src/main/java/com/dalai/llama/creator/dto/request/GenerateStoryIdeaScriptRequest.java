package com.dalai.llama.creator.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateStoryIdeaScriptRequest(
        @Min(15) @Max(10800) Integer durationSeconds,
        @Size(max = 64) String categoryCode,
        @Size(max = 4000) String idea,
        @Size(max = 64) String dialogueLanguage,
        @Size(max = 32) String screenType,
        @Size(max = 64) String storytellingType,
        @Size(max = 64) String hookLens,
        @Size(max = 32) String budgetTier,
        @Size(max = 32) String productionStyle,
        @Size(max = 64) String hybridSceneMode,
        @Size(max = 64) String brollStyle,
        @Size(max = 64) String captionStyle,
        JsonNode productionStyleGuidance,
        JsonNode screenplayVideoGenerationPackage,
        List<@Valid CharacterCastMappingContext> characterCastMappings,
        List<@Valid ActorContext> availableActors,
        @Valid AudienceDecisionContext audienceDecision,
        @Valid BrandContext brandContext,
        @Valid CreatorContext creatorContext,
        @Valid WorkflowContext context
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CharacterCastMappingContext(
            @Size(max = 160) String characterKey,
            @Size(max = 80) String scriptCharacterId,
            @Size(max = 160) String characterName,
            @Size(max = 120) String characterRole,
            @Size(max = 80) String castProfileId,
            @Size(max = 160) String actorName,
            Boolean confirmed,
            @Valid ActorContext actor,
            JsonNode castPayload,
            JsonNode characterPayload
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActorContext(
            @Size(max = 80) String id,
            @Size(max = 80) String actorId,
            @Size(max = 160) String displayName,
            @Size(max = 160) String name,
            @Size(max = 120) String roleInShort,
            Boolean confirmed,
            @Size(max = 64) String gender,
            @Size(max = 64) String age,
            @Size(max = 64) String ageRange,
            @Size(max = 500) String look,
            @Size(max = 1000) String profile,
            @Size(max = 120) String style,
            @Size(max = 120) String cameraConfidence,
            JsonNode attributes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AudienceDecisionContext(
            @Size(max = 80) String id,
            @Size(max = 200) String title,
            @Size(max = 2000) String description,
            @Size(max = 80) String gender,
            @Size(max = 80) String ageGroup,
            @Size(max = 120) String location,
            List<@Size(max = 120) String> interests,
            @Size(max = 240) String contentPreference,
            @Size(max = 240) String tonePreference,
            Boolean aiSuggested,
            Boolean confirmed,
            JsonNode demographics,
            JsonNode psychographics
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BrandContext(
            @Size(max = 80) String id,
            @Size(max = 200) String brandName,
            @Size(max = 200) String productName,
            @Size(max = 120) String productCategory,
            @Size(max = 4000) String offer,
            @Size(max = 240) String campaignObjective,
            @Size(max = 240) String brandTone,
            @Size(max = 240) String cta,
            List<@Size(max = 2000) String> requiredMentions,
            List<@Size(max = 2000) String> bannedClaims,
            List<@Size(max = 2000) String> restrictions,
            @Valid VisualIdentityContext visualIdentity,
            JsonNode metadata
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VisualIdentityContext(
            List<@Size(max = 64) String> colors,
            Boolean logoRequired,
            @Size(max = 1000) String styleNotes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreatorContext(
            @Size(max = 240) String shootingLocation,
            List<@Size(max = 120) String> availableProps,
            List<@Size(max = 120) String> availableGear,
            @Size(max = 240) String creatorComfort,
            @Size(max = 240) String languageStyle,
            @Min(15) @Max(10800) Integer durationSeconds,
            @Size(max = 64) String dialogueLanguage,
            @Size(max = 32) String screenType,
            @Size(max = 64) String storytellingType,
            @Size(max = 64) String hookLens,
            @Size(max = 32) String productionStyle,
            @Size(max = 64) String hybridSceneMode,
            @Size(max = 64) String brollStyle,
            @Size(max = 64) String captionStyle,
            JsonNode productionStyleGuidance,
            JsonNode metadata
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkflowContext(
            @Valid AiProviderContext aiProvider,
            @Size(max = 64) String workflowLockedAt,
            @Valid ScreenplayProductionPackage lockedPackage,
            @Size(max = 64) String storytellingType,
            @Size(max = 64) String hookLens,
            JsonNode metadata
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiProviderContext(
            @Size(max = 80) String providerCode,
            @Size(max = 80) String provider,
            @Size(max = 160) String providerLabel,
            @Size(max = 80) String providerType,
            @Size(max = 160) String model,
            Boolean credentialConfigured
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScreenplayProductionPackage(
            @Size(max = 32) String budgetTier,
            List<@Valid CharacterCastMappingContext> characterCastMappings,
            List<@Valid ActorContext> availableActors,
            @Valid AudienceDecisionContext audienceDecision,
            @Valid BrandContext brandContext,
            @Valid CreatorContext creatorContext,
            @Size(max = 64) String storytellingType,
            @Size(max = 64) String hookLens,
            @Size(max = 32) String productionStyle,
            @Size(max = 64) String hybridSceneMode,
            @Size(max = 64) String brollStyle,
            @Size(max = 64) String captionStyle,
            JsonNode productionStyleGuidance,
            JsonNode screenplayVideoGenerationPackage
    ) {
    }
}
