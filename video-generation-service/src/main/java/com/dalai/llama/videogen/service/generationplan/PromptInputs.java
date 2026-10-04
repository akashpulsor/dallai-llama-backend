package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.domain.entity.ShotGenerationPlan;
import com.dalai.llama.videogen.dto.generationplan.PromptInputView;
import com.dalai.llama.videogen.dto.shotcontext.Camera;
import com.dalai.llama.videogen.dto.shotcontext.Lighting;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.ShotContextAssemblyService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a shot's video prompt is built from, item by item, read from the same assembled shot the
 * prompt is written from -- so "present" means "the model is given this", not "the plan has it
 * somewhere".
 */
public final class PromptInputs {

    private static final int DETAIL_CHARS = 220;
    private static final Pattern DIRECTION_TITLE = Pattern.compile("APPROVED CREATIVE DIRECTION: \"([^\"]*)\"");

    private PromptInputs() {
    }

    public static List<PromptInputView> of(ShotContextAssemblyService.AssembledShot assembled, ShotGenerationPlan plan) {
        ShotContext shot = assembled.shotContext();
        ShotContextAssemblyService.ShotPromptSources sources = assembled.sources();
        List<PromptInputView> inputs = new ArrayList<>();

        String direction = sources == null ? null : sources.approvedCreativeDirection();
        boolean directionApproved = direction != null && !direction.isBlank() && direction.contains("APPROVED CREATIVE DIRECTION");
        inputs.add(new PromptInputView("creativeDirection", "Approved creative direction", directionApproved,
                directionApproved ? "\"" + title(direction) + "\"" : "None approved for this project."));

        String action = shot.narrative() == null ? null : shot.narrative().scriptLine();
        inputs.add(item("script", "Script line and action", action));

        String storyFrame = shot.narrative() == null ? null : shot.narrative().screenplaySlug();
        inputs.add(item("storyFrame", "Story frame (hook, beat plan, arc)", storyFrame));
        inputs.add(new PromptInputView("screenplayScene", "Screenplay scene", false,
                "Not sent: the bundle carries only the scene's id. The script's story frame stands in for it."));

        String dialogue = shot.dialogueBeats() != null && !shot.dialogueBeats().isEmpty()
                ? shot.dialogueBeats().stream().map(b -> b.text()).filter(Objects::nonNull).collect(Collectors.joining(" / "))
                : shot.narrative() == null ? null : shot.narrative().dialogue();
        inputs.add(item("dialogue", "Dialogue", dialogue));

        int frames = shot.referenceFrames() == null ? 0 : shot.referenceFrames().size();
        inputs.add(new PromptInputView("shotFrame", "Shot frame (storyboard / production image)", frames > 0,
                frames > 0 ? shot.referenceFrames().get(0).kind() + " image attached as a reference" : "No frame image generated yet."));

        List<String> cast = shot.characters() == null ? List.of() : shot.characters().stream()
                .filter(c -> c.faceRefObjectKey() != null)
                .map(c -> c.name() == null ? "unnamed" : c.name()).toList();
        inputs.add(new PromptInputView("cast", "Cast faces", !cast.isEmpty(),
                cast.isEmpty() ? "No cast face attached." : String.join(", ", cast)));

        Lighting lighting = shot.lighting();
        boolean lightingPlanned = sources != null && sources.lightingPlanId() != null;
        inputs.add(new PromptInputView("lighting", "Lighting plan", lightingPlanned,
                lightingPlanned ? clip(lightingSummary(lighting)) : "No lighting plan for this shot."));

        Camera camera = shot.camera();
        boolean cameraPlanned = sources != null && sources.cameraPlanId() != null;
        inputs.add(new PromptInputView("camera", "Camera plan", cameraPlanned,
                cameraPlanned ? clip(cameraSummary(camera)) : "No camera plan for this shot."));

        boolean product = shot.productBrand() != null && shot.productBrand().productRefObjectKey() != null;
        inputs.add(new PromptInputView("product", "Product reference", product,
                product ? "Product image attached" + (shot.productBrand().productPlacement() == null ? "" : " -- " + shot.productBrand().productPlacement())
                        : "No product reference."));

        boolean music = shot.audioAmbience() != null && shot.audioAmbience().backgroundMusicObjectKey() != null;
        inputs.add(new PromptInputView("music", "Background music", music,
                music ? "Mixed under the clip after generation" : "No music bed for this shot."));

        int images = shot.referenceImages() == null ? 0 : shot.referenceImages().size();
        inputs.add(new PromptInputView("referenceImages", "Creator reference images", images > 0,
                images > 0 ? images + " image(s) attached in your order" : "None uploaded."));

        if (shot.isPlannedMotionGraphic()) {
            inputs.add(item("motionGraphic", "Motion graphic plan", shot.motionGraphic().concept()));
        }

        boolean frameReady = plan != null && plan.getContinuationFrameObjectKey() != null;
        inputs.add(new PromptInputView("previousLastFrame", "Previous shot's last frame", frameReady,
                frameReady ? "Attached as reference image 1 -- the clip opens on it"
                        : plan != null && plan.getContinuationRequestId() != null ? "Being taken by post-production"
                        : "Not attached."));

        boolean timeline = plan != null && plan.getTimelineDurationSeconds() != null
                && Objects.equals(plan.getTimelineDurationSeconds(), plan.getGenerationDurationSeconds());
        inputs.add(new PromptInputView("timeline", "Second-by-second timeline", timeline,
                timeline ? "Built for " + plan.getTimelineDurationSeconds() + "s" : "Not built for the selected duration."));
        return inputs;
    }

    private static PromptInputView item(String key, String label, String text) {
        boolean present = text != null && !text.isBlank();
        return new PromptInputView(key, label, present, present ? clip(text) : "Not in the plan.");
    }

    private static String title(String block) {
        Matcher matcher = DIRECTION_TITLE.matcher(block);
        return matcher.find() ? matcher.group(1) : "approved";
    }

    private static String lightingSummary(Lighting lighting) {
        if (lighting == null) {
            return "Planned";
        }
        return joined(lighting.mood() == null ? null : "mood " + lighting.mood().name().toLowerCase(),
                lighting.keyLightNote(), lighting.keyLightGear() == null ? null : "key: " + lighting.keyLightGear(),
                lighting.fillLightGear() == null ? null : "fill: " + lighting.fillLightGear());
    }

    private static String cameraSummary(Camera camera) {
        if (camera == null) {
            return "Planned";
        }
        return joined(camera.shotSize() == null ? null : camera.shotSize().name(), camera.lensFocalLength(),
                camera.movementType(), camera.framing());
    }

    private static String joined(String... parts) {
        String text = java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(", "));
        return text.isEmpty() ? "Planned" : text;
    }

    private static String clip(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= DETAIL_CHARS ? flat : flat.substring(0, DETAIL_CHARS - 1) + "…";
    }
}
