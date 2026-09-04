package com.dalai.llama.llmgateway.service.prompt;

import com.dalai.llama.llmgateway.dto.prompt.PromptDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fallback strategy: matches every model_id so the resolver always has something to fall back
 * to. Line-per-directive composition (identical to what {@code DefaultPromptBuilderService}
 * produced before the strategy refactor -- nothing regresses for models without a dedicated
 * strategy).
 */
@Component
@Order(Integer.MAX_VALUE)
public class DefaultPromptStrategy implements ProviderPromptStrategy {

    private final NegativePromptComposer negativePromptComposer;
    private final DialoguePhonemeService dialoguePhonemeService;
    private final int maxPromptLength;

    public DefaultPromptStrategy(
            NegativePromptComposer negativePromptComposer,
            DialoguePhonemeService dialoguePhonemeService,
            @Value("${llm-gateway.prompt-format.default-max-prompt-length:2000}") int maxPromptLength
    ) {
        this.negativePromptComposer = negativePromptComposer;
        this.dialoguePhonemeService = dialoguePhonemeService;
        this.maxPromptLength = maxPromptLength;
    }

    @Override
    public boolean supports(String modelId) {
        return true;
    }

    @Override
    public int maxPromptLength() {
        return maxPromptLength;
    }

    @Override
    public Built build(PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags flags) {
        return new Built(composePositive(shotContext, flags), negativePromptComposer.compose(shotContext, flags));
    }

    private String composePositive(PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags flags) {
        List<String> lines = new ArrayList<>();
        if (shotContext.narrative() != null && shotContext.narrative().scriptLine() != null) {
            lines.add("Action: " + shotContext.narrative().scriptLine());
        }
        if (shotContext.characters() != null) {
            for (PromptDtos.Character character : shotContext.characters()) {
                if (character.wardrobeNote() != null) lines.add("Wardrobe: " + character.wardrobeNote());
                if (character.performanceDirection() != null) lines.add("Performance: " + character.performanceDirection());
            }
        }
        if (shotContext.environment() != null) {
            if (shotContext.environment().location() != null) lines.add("Location: " + shotContext.environment().location());
            if (shotContext.environment().weather() != null) lines.add("Weather: " + shotContext.environment().weather());
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
            if (shotContext.audioAmbience().ambientDescription() != null) lines.add("Ambient sound: " + shotContext.audioAmbience().ambientDescription());
            if (shotContext.audioAmbience().musicMoodNote() != null) lines.add("Music mood: " + shotContext.audioAmbience().musicMoodNote());
        }
        if (shotContext.continuityAnchors() != null) {
            shotContext.continuityAnchors().forEach(anchor -> {
                if (anchor.description() != null) lines.add("Continuity: " + anchor.description());
            });
        }
        if (shotContext.technical() != null && shotContext.technical().editingNotes() != null
                && !shotContext.technical().editingNotes().isBlank()) {
            lines.add("Editing: " + shotContext.technical().editingNotes());
        }
        String dialogueLine = shotContext.narrative() != null ? shotContext.narrative().scriptLine() : null;
        if (flags != null && PromptDtos.FeatureFlags.ON.equals(flags.dialogue()) && dialogueLine != null && !dialogueLine.isBlank()) {
            String respelled = dialoguePhonemeService.respellDialogue(dialogueLine, "en");
            lines.add("Dialogue/VO: " + respelled);
        }
        return String.join("\n", lines);
    }
}
