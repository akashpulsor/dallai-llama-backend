package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.FlagState;
import com.dalai.llama.videogen.domain.LibraryScope;
import com.dalai.llama.videogen.domain.entity.NegativePromptLibrary;
import com.dalai.llama.videogen.dto.FeatureFlags;
import com.dalai.llama.videogen.dto.shotcontext.Character;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.repository.NegativePromptLibraryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Doc §5's composition step, rebuilt with typed inputs instead of the
 * Map&lt;String,Object&gt;-juggling ScreenplayVideoProviderGenerationService.buildConsistencyPrompt
 * does in creator-service -- this is the "staff-level DTO" requirement in practice.
 */
@Service
public class DefaultPromptBuilderService implements PromptBuilderService {

    private final NegativePromptLibraryRepository negativePromptLibraryRepository;
    private final DialoguePhonemeService dialoguePhonemeService;

    public DefaultPromptBuilderService(
            NegativePromptLibraryRepository negativePromptLibraryRepository,
            DialoguePhonemeService dialoguePhonemeService
    ) {
        this.negativePromptLibraryRepository = negativePromptLibraryRepository;
        this.dialoguePhonemeService = dialoguePhonemeService;
    }

    @Override
    public BuiltPrompt buildPrompt(ShotContext shotContext, FeatureFlags effectiveFlags) {
        return new BuiltPrompt(
                composePositive(shotContext, effectiveFlags),
                composeNegative(shotContext, effectiveFlags)
        );
    }

    private String composePositive(ShotContext shotContext, FeatureFlags flags) {
        List<String> lines = new ArrayList<>();
        if (shotContext.narrative() != null && shotContext.narrative().scriptLine() != null) {
            lines.add("Action: " + shotContext.narrative().scriptLine());
        }
        if (shotContext.characters() != null) {
            for (Character character : shotContext.characters()) {
                if (character.wardrobeNote() != null) {
                    lines.add("Wardrobe: " + character.wardrobeNote());
                }
                if (character.performanceDirection() != null) {
                    lines.add("Performance: " + character.performanceDirection());
                }
            }
        }
        if (shotContext.environment() != null) {
            if (shotContext.environment().location() != null) {
                lines.add("Location: " + shotContext.environment().location());
            }
            if (shotContext.environment().weather() != null) {
                lines.add("Weather: " + shotContext.environment().weather());
            }
        }
        if (shotContext.lighting() != null && shotContext.lighting().keyLightNote() != null) {
            lines.add("Lighting: " + shotContext.lighting().keyLightNote());
        }
        if (shotContext.camera() != null && shotContext.camera().cameraNote() != null) {
            lines.add("Camera: " + shotContext.camera().cameraNote());
        }
        if (shotContext.productBrand() != null && Boolean.TRUE.equals(shotContext.productBrand().isProductHeroShot())
                && shotContext.productBrand().productPlacement() != null) {
            lines.add("Product: " + shotContext.productBrand().productPlacement());
        }
        if (shotContext.audioAmbience() != null) {
            // doc §6 (revised): ambient/music description composed straight into the same
            // prompt -- one dispatch, the model's own native audio synthesis produces it.
            if (shotContext.audioAmbience().ambientDescription() != null) {
                lines.add("Ambient sound: " + shotContext.audioAmbience().ambientDescription());
            }
            if (shotContext.audioAmbience().musicMoodNote() != null) {
                lines.add("Music mood: " + shotContext.audioAmbience().musicMoodNote());
            }
        }
        if (shotContext.continuityAnchors() != null) {
            shotContext.continuityAnchors().forEach(anchor -> {
                if (anchor.description() != null) {
                    lines.add("Continuity: " + anchor.description());
                }
            });
        }

        String dialogueLine = shotContext.narrative() != null ? shotContext.narrative().scriptLine() : null;
        if (flags.dialogue() == FlagState.ON && dialogueLine != null && !dialogueLine.isBlank()) {
            String respelled = dialoguePhonemeService.respellDialogue(dialogueLine, "en");
            lines.add("Dialogue/VO: " + respelled);
        }

        return String.join("\n", lines);
    }

    private String composeNegative(ShotContext shotContext, FeatureFlags flags) {
        List<String> snippets = new ArrayList<>();
        negativePromptLibraryRepository.findByScope(LibraryScope.BASE)
                .forEach(s -> snippets.add(s.getContent()));
        String targetProvider = shotContext.technical() != null ? shotContext.technical().targetProvider() : null;
        if (targetProvider != null) {
            negativePromptLibraryRepository.findByScopeAndProviderId(LibraryScope.PROVIDER, targetProvider)
                    .forEach(s -> snippets.add(s.getContent()));
        }
        // doc §19: a suppressed layer gets an explicit negative directive, not just an omission,
        // so the video model doesn't accidentally render a poorly-formed version anyway.
        if (flags.captions() == FlagState.OFF) {
            snippets.add("no text, no typography, no captions, no subtitles");
        }
        return snippets.stream().distinct().collect(Collectors.joining(", "));
    }
}
