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

    @Override
    public boolean supports(String modelId) {
        return modelId != null && modelId.startsWith("alibaba/wan");
    }

    @Override
    public int maxPromptLength() {
        return WAN_MAX_PROMPT_LENGTH;
    }

    @Override
    public Built build(String tenantId, PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags flags) {
        return new Built(composePositive(tenantId, shotContext, flags), negativePromptComposer.compose(shotContext, flags));
    }

    private void addClause(List<String> clauses, String value) {
        if (value != null && !value.isBlank()) {
            clauses.add(value.trim());
        }
    }

    /** Wan reads one comma-separated descriptive sentence, not a labelled spec sheet, so the
     * cinematography goes in as bare clauses -- "85mm", "shallow depth of field", "slow dolly in"
     * -- rather than the default strategy's "Lens: ..." lines.
     *
     * <p>Every specification the shot has is emitted, including the ones that will often push the
     * result past Wan's budget. That is deliberate: fitting the budget is PROMPT_COMPRESSION's
     * job, and it rewrites rather than truncates, so a shot plan that is too long comes back
     * shorter with everything still in it. Dropping fields here instead would decide -- silently,
     * and for every shot regardless of how much room it actually needed -- that the creator's
     * lighting and exposure choices do not matter. */
    private void addCinematography(List<String> clauses, PromptDtos.Camera camera) {
        if (camera == null) {
            return;
        }
        addClause(clauses, camera.framing());
        addClause(clauses, camera.subjectPlacement());
        addClause(clauses, camera.headroom());
        addClause(clauses, camera.leadRoom());
        addClause(clauses, camera.visualBalance());
        addClause(clauses, camera.positionHeight());
        addClause(clauses, camera.positionDistance());
        addClause(clauses, camera.positionLateral());
        addClause(clauses, camera.positionElevation());
        addClause(clauses, camera.positionOrientation());
        addClause(clauses, camera.lensFocalLength());
        addClause(clauses, camera.lensType());
        addClause(clauses, camera.lensOpticalFormat());
        addClause(clauses, camera.lensDistortion());
        addClause(clauses, camera.lensCompression());
        addClause(clauses, camera.lensCharacter());
        addClause(clauses, camera.focusTarget());
        addClause(clauses, camera.focusDistance());
        addClause(clauses, camera.depthOfField());
        addClause(clauses, camera.rackFocus());
        addClause(clauses, camera.focusBehaviour());
        addClause(clauses, camera.movementType());
        addClause(clauses, camera.movementTrajectory());
        addClause(clauses, camera.movementSpeed());
        addClause(clauses, camera.movementAcceleration());
        addClause(clauses, camera.movementRotation());
        addClause(clauses, camera.movementSubjectRelationship());
        addClause(clauses, camera.support());
        addClause(clauses, camera.cameraBody());
        addClause(clauses, camera.sensor());
        addClause(clauses, camera.captureFormat());
        addClause(clauses, camera.recordingCharacteristics());
        addClause(clauses, camera.aperture());
        addClause(clauses, camera.iso());
        addClause(clauses, camera.shutter());
        addClause(clauses, camera.ndFilter());
        addClause(clauses, camera.dynamicRange());
        addClause(clauses, camera.shutterAngle());
        addClause(clauses, camera.motionBlur());
        addClause(clauses, camera.slowMotion());
        addClause(clauses, camera.filtrationDiffusion());
        addClause(clauses, camera.filtrationNd());
        addClause(clauses, camera.filtrationPolarizer());
        addClause(clauses, camera.filtrationSpecialty());
        addClause(clauses, camera.contrast());
        addClause(clauses, camera.colorResponse());
        addClause(clauses, camera.grain());
        addClause(clauses, camera.halation());
        addClause(clauses, camera.bloom());
        addClause(clauses, camera.sharpness());
        addClause(clauses, camera.flare());
    }

    private String composePositive(String tenantId, PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags flags) {
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
        if (shotContext.lighting() != null) {
            if (shotContext.lighting().keyLightNote() != null && !shotContext.lighting().keyLightNote().isBlank()) {
                clauses.add(shotContext.lighting().keyLightNote());
            }
            addClause(clauses, shotContext.lighting().mood());
        }
        if (shotContext.camera() != null && shotContext.camera().cameraNote() != null
                && !shotContext.camera().cameraNote().isBlank()) {
            clauses.add(shotContext.camera().cameraNote());
        }
        addCinematography(clauses, shotContext.camera());
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
        String dialogueLine = shotContext.narrative() != null ? shotContext.narrative().dialogue() : null;
        if (flags != null && PromptDtos.FeatureFlags.ON.equals(flags.dialogue()) && dialogueLine != null && !dialogueLine.isBlank()) {
            // As written -- see DefaultPromptStrategy for why the phonetic respelling round-trip
            // is gone: the dialogue is already dubbed by the time a prompt is composed.
            clauses.add("with spoken line: \"" + dialogueLine + "\"");
        }
        return String.join(", ", clauses);
    }
}
