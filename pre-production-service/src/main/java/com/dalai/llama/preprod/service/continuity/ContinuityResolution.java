package com.dalai.llama.preprod.service.continuity;

import com.dalai.llama.preprod.service.generation.PromptInput;
import com.dalai.llama.preprod.service.generation.PromptInputs;

import java.util.List;
import java.util.Map;

/**
 * A step shot's resolved visual state with full provenance, plus the replacements the prompt
 * builder must apply. The prompt is built from this, never from the raw sources, so what the UI
 * shows and what the model is told are the same decision.
 *
 * @param resolvedVisualState every field that has a value, with where it came from and why it won
 * @param overrides           the decisions worth showing: replacements, transitions, user choices, warnings
 * @param warnings            things that could not be resolved cleanly
 * @param substitutions       prompt inputs replaced (or dropped, value null) to keep continuity
 */
public record ContinuityResolution(Map<VisualField, ResolvedValue> resolvedVisualState,
                                   List<VisualOverride> overrides,
                                   List<String> warnings,
                                   List<String> changesForThisShot,
                                   String newElementLighting,
                                   Map<PromptInput, Substitution> substitutions) {

    public static final ContinuityResolution NONE = new ContinuityResolution(Map.of(), List.of(), List.of(), List.of(), null, Map.of());

    /** How the builder reads inputs under this resolution. */
    public PromptInputs inputs() {
        return (input, raw) -> substitutions.containsKey(input) ? substitutions.get(input).value() : raw;
    }

    /** Fields kept from the reference image with nothing competing -- the compact "preserved" line. */
    public List<VisualField> preserved() {
        return resolvedVisualState.entrySet().stream()
                .filter(entry -> entry.getValue().severity() == ResolutionSeverity.PRESERVED)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** @param competing every other value that was considered for this field */
    public record ResolvedValue(String value, ContinuitySource source, ResolutionSeverity severity,
                                String reason, List<Competitor> competing) {}

    public record Competitor(PromptInput input, ContinuitySource source, String value) {}

    /** One UI row per field. {@code originalValue}/{@code originalSource} is what would have been used
     * without continuity (the strongest competing value); {@code superseded} lists every competitor it
     * stands for, so five lighting lines that disagree with the image are one row, not five. */
    public record VisualOverride(VisualField field, String originalValue, String resolvedValue,
                                 ContinuitySource originalSource, ContinuitySource resolvedSource,
                                 String reason, ResolutionSeverity severity, boolean userOverridable,
                                 List<Competitor> superseded) {}

    /** @param value what the prompt says instead of the raw input; null drops the input */
    public record Substitution(String value, VisualField field) {}
}
