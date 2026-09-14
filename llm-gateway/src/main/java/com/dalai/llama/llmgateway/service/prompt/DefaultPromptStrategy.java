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
    private final int maxPromptLength;

    public DefaultPromptStrategy(
            NegativePromptComposer negativePromptComposer,
            @Value("${llm-gateway.prompt-format.default-max-prompt-length:2000}") int maxPromptLength
    ) {
        this.negativePromptComposer = negativePromptComposer;
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
    public Built build(String tenantId, PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags flags) {
        return new Built(composePositive(tenantId, shotContext, flags), negativePromptComposer.compose(shotContext, flags));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Appends only the parts the shot actually specifies, grouped so the model reads one lens
     * line rather than six loose adjectives. Grouping also keeps the cost down: an unspecified
     * group contributes nothing instead of a line of empty labels.
     *
     * <p>Body/exposure/filtration are folded into one "Shot on" line because a video model treats
     * them as look, not as instructions it can act on individually. */
    private void addCinematography(List<String> lines, PromptDtos.Camera camera) {
        if (camera == null) {
            return;
        }
        addGroup(lines, "Framing", camera.framing(), camera.subjectPlacement(), camera.headroom(),
                camera.leadRoom(), camera.visualBalance());
        addGroup(lines, "Camera position", camera.positionHeight(), camera.positionDistance(),
                camera.positionLateral(), camera.positionElevation(), camera.positionOrientation());
        addGroup(lines, "Lens", camera.lensFocalLength(), camera.lensType(), camera.lensOpticalFormat(),
                camera.lensDistortion(), camera.lensCompression(), camera.lensCharacter());
        addGroup(lines, "Focus", camera.focusTarget(), camera.focusDistance(), camera.depthOfField(),
                camera.rackFocus(), camera.focusBehaviour());
        addGroup(lines, "Camera movement", camera.movementType(), camera.movementTrajectory(),
                camera.movementSpeed(), camera.movementAcceleration(), camera.movementRotation(),
                camera.movementSubjectRelationship(), camera.support());
        addGroup(lines, "Motion", camera.shutterAngle(), camera.motionBlur(), camera.slowMotion());
        addGroup(lines, "Shot on", camera.cameraBody(), camera.sensor(), camera.captureFormat(),
                camera.recordingCharacteristics(), camera.aperture(), camera.iso(), camera.shutter(),
                camera.ndFilter(), camera.dynamicRange(), camera.filtrationDiffusion(), camera.filtrationNd(),
                camera.filtrationPolarizer(), camera.filtrationSpecialty());
        addGroup(lines, "Image character", camera.contrast(), camera.colorResponse(), camera.grain(),
                camera.halation(), camera.bloom(), camera.sharpness(), camera.flare());
    }

    private void addGroup(List<String> lines, String label, String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) {
            if (hasText(value)) {
                present.add(value.trim());
            }
        }
        if (!present.isEmpty()) {
            lines.add(label + ": " + String.join(", ", present));
        }
    }

    private String composePositive(String tenantId, PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags flags) {
        List<String> lines = new ArrayList<>();
        if (shotContext.narrative() != null && shotContext.narrative().scriptLine() != null) {
            lines.add("Action: " + shotContext.narrative().scriptLine());
        }
        // The project's story frame (hook, beat plan, arc). Every shot in a project carries the
        // same one -- that is deliberate: it is what stops each shot being generated as if it were
        // a standalone clip with no idea what the film around it is doing.
        if (shotContext.narrative() != null && hasText(shotContext.narrative().screenplaySlug())) {
            lines.add("Story context: " + shotContext.narrative().screenplaySlug());
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
        if (shotContext.lighting() != null) {
            if (shotContext.lighting().keyLightNote() != null) {
                lines.add("Lighting: " + shotContext.lighting().keyLightNote());
            }
            // Was populated by the caller and read by nobody until now.
            if (hasText(shotContext.lighting().mood())) {
                lines.add("Lighting mood: " + shotContext.lighting().mood());
            }
        }
        if (shotContext.camera() != null && shotContext.camera().cameraNote() != null) {
            lines.add("Camera: " + shotContext.camera().cameraNote());
        }
        addCinematography(lines, shotContext.camera());
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
        // The resolution the caller actually asked for -- resolved from the per-request override
        // or the project config before it ever got here. The prompt states it because it is the
        // one authority on the question; captureFormat describes the LOOK (codec, bit depth) and
        // is not asked to carry a resolution.
        if (shotContext.technical() != null && hasText(shotContext.technical().resolution())) {
            lines.add("Output resolution: " + shotContext.technical().resolution());
        }
        if (shotContext.technical() != null && shotContext.technical().editingNotes() != null
                && !shotContext.technical().editingNotes().isBlank()) {
            lines.add("Editing: " + shotContext.technical().editingNotes());
        }
        String dialogueLine = shotContext.narrative() != null ? shotContext.narrative().dialogue() : null;
        if (flags != null && PromptDtos.FeatureFlags.ON.equals(flags.dialogue()) && dialogueLine != null && !dialogueLine.isBlank()) {
            // The line goes in as written. Phonetic respelling used to happen here, which put a
            // synchronous PHONEME_GUIDE LLM round-trip inside prompt composition -- 14-22s per
            // shot, the single largest cost in prepare. It bought nothing: respelling exists to
            // help a TTS engine pronounce a line, and by this point the dialogue has already been
            // dubbed by BeatDubbingService. The video model is being told what is said, not asked
            // to say it.
            lines.add("Dialogue/VO: " + dialogueLine);
        }
        return String.join("\n", lines);
    }
}
