package com.dalai.llama.llmgateway.service.prompt;

import com.dalai.llama.llmgateway.dto.prompt.PromptDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Alibaba Wan (alibaba/wan-3.0-prime and siblings) prefers a single flowing paragraph over
 * line-per-directive lists -- verified against fal.ai's real Wan model page examples. Max
 * prompt length is Wan's documented ~1000 char limit; going over silently truncates on their
 * side.
 */
@Component
@RequiredArgsConstructor
public class WanPromptStrategy implements ProviderPromptStrategy {

    private static final int WAN_MAX_PROMPT_LENGTH = 1000;

    private final NegativePromptComposer negativePromptComposer;
    private final DialoguePhonemeService dialoguePhonemeService;

    @Override
    public boolean supports(String modelId) {
        return modelId != null && modelId.startsWith("alibaba/wan");
    }

    @Override
    public int maxPromptLength() {
        return WAN_MAX_PROMPT_LENGTH;
    }

    @Override
    public Built build(PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags flags) {
        return new Built(composePositive(shotContext, flags), negativePromptComposer.compose(shotContext, flags));
    }

    private String composePositive(PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags flags) {
        List<String> clauses = new ArrayList<>();
        if (shotContext.narrative() != null && shotContext.narrative().scriptLine() != null
                && !shotContext.narrative().scriptLine().isBlank()) {
            clauses.add(shotContext.narrative().scriptLine());
        }
        if (shotContext.characters() != null) {
            for (PromptDtos.Character character : shotContext.characters()) {
                if (character.wardrobeNote() != null && !character.wardrobeNote().isBlank()) {
                    clauses.add("wearing " + character.wardrobeNote());
                }
                if (character.performanceDirection() != null && !character.performanceDirection().isBlank()) {
                    clauses.add(character.performanceDirection());
                }
            }
        }
        if (shotContext.environment() != null && shotContext.environment().location() != null) {
            String setting = "in " + shotContext.environment().location();
            if (shotContext.environment().weather() != null && !shotContext.environment().weather().isBlank()) {
                setting += " with " + shotContext.environment().weather();
            }
            clauses.add(setting);
        }
        if (shotContext.lighting() != null && shotContext.lighting().keyLightNote() != null
                && !shotContext.lighting().keyLightNote().isBlank()) {
            clauses.add(shotContext.lighting().keyLightNote());
        }
        if (shotContext.camera() != null && shotContext.camera().cameraNote() != null
                && !shotContext.camera().cameraNote().isBlank()) {
            clauses.add(shotContext.camera().cameraNote());
        }
        if (shotContext.productBrand() != null
                && Boolean.TRUE.equals(shotContext.productBrand().isProductHeroShot())
                && shotContext.productBrand().productPlacement() != null) {
            clauses.add(shotContext.productBrand().productPlacement());
        }
        if (shotContext.continuityAnchors() != null) {
            shotContext.continuityAnchors().forEach(anchor -> {
                if (anchor.description() != null && !anchor.description().isBlank()) {
                    clauses.add(anchor.description());
                }
            });
        }
        String dialogueLine = shotContext.narrative() != null ? shotContext.narrative().scriptLine() : null;
        if (flags != null && PromptDtos.FeatureFlags.ON.equals(flags.dialogue()) && dialogueLine != null && !dialogueLine.isBlank()) {
            String respelled = dialoguePhonemeService.respellDialogue(dialogueLine, "en");
            clauses.add("with spoken line: \"" + respelled + "\"");
        }
        return String.join(", ", clauses);
    }
}
