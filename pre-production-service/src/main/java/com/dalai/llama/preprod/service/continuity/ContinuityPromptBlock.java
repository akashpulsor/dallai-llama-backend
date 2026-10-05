package com.dalai.llama.preprod.service.continuity;

import com.dalai.llama.preprod.service.continuity.ContinuityResolution.ResolvedValue;

import java.util.List;
import java.util.Map;

/** The CRITICAL VISUAL CONTINUITY block that leads a step-shot prompt, written only from the
 * resolved state: what the image establishes (keep), what this shot changes and why, how new
 * elements take the existing light, and what not to change. */
public final class ContinuityPromptBlock {

    static final String DEFAULT_NEW_ELEMENT_LIGHTING = "Light every newly introduced element with the illumination already in the "
            + "image -- match its direction, intensity, colour temperature, reflections and shadows.";

    private ContinuityPromptBlock() {}

    public static String render(ContinuityResolution resolution) {
        StringBuilder sb = new StringBuilder()
                .append("CRITICAL VISUAL CONTINUITY -- the attached image is authoritative for everything it already establishes. ")
                .append("Only what this shot explicitly changes may differ; if any later line conflicts with the image, the image wins. ")
                .append("Do not reinterpret or relight the scene from the shot description's generic metadata.\n");

        List<Map.Entry<VisualField, ResolvedValue>> kept = resolution.resolvedVisualState().entrySet().stream()
                .filter(entry -> entry.getValue().source() == ContinuitySource.REFERENCE_IMAGE).toList();
        sb.append("Keep exactly as in the image:\n");
        kept.forEach(entry -> sb.append("- ").append(entry.getKey().label()).append(": ").append(entry.getValue().value()).append('\n'));
        sb.append("- every person who stays: identical in face, hair, skin tone, build and wardrobe\n");

        sb.append("This shot changes:\n");
        resolution.resolvedVisualState().forEach((field, value) -> {
            if (value.severity() == ResolutionSeverity.EXPLICIT_TRANSITION || value.severity() == ResolutionSeverity.USER_OVERRIDE) {
                sb.append("- ").append(field.label()).append(": ").append(value.value()).append(" -- ")
                        .append(value.severity() == ResolutionSeverity.USER_OVERRIDE ? "a deliberate change" : value.reason()).append('\n');
            }
        });
        resolution.changesForThisShot().forEach(change -> sb.append("- ").append(change.trim()).append('\n'));
        sb.append("- camera position, angle, lens, framing and composition: as the shot description below says\n");

        sb.append("New elements: ").append(resolution.newElementLighting() != null
                ? resolution.newElementLighting() : DEFAULT_NEW_ELEMENT_LIGHTING).append('\n');

        if (!kept.isEmpty()) {
            sb.append("Do not: ");
            sb.append(String.join("; ", kept.stream().map(entry -> entry.getKey().forbiddenChange()).toList()));
            sb.append(".\n");
        }
        return sb.toString();
    }
}
