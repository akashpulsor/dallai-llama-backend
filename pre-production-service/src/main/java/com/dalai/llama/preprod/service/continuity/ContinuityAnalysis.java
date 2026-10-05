package com.dalai.llama.preprod.service.continuity;

import com.dalai.llama.preprod.service.generation.PromptInput;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * What the analysis model read from the reference image and the new shot -- facts only, no
 * decisions. {@link ContinuityResolver} turns this into the resolved state deterministically, so
 * the priority order lives in code, not in a model's judgement.
 *
 * @param fields             one entry per {@link VisualField} the image or the inputs say anything about
 * @param changesForThisShot what the shot itself introduces or changes (new subjects, action, camera)
 * @param newElementLighting how newly introduced elements take the light already in the image
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContinuityAnalysis(String referenceSummary, List<FieldAnalysis> fields,
                                 List<String> changesForThisShot, String newElementLighting) {

    public static final ContinuityAnalysis EMPTY = new ContinuityAnalysis(null, List.of(), List.of(), null);

    public List<FieldAnalysis> fields() {
        return fields == null ? List.of() : fields;
    }

    public List<String> changesForThisShot() {
        return changesForThisShot == null ? List.of() : changesForThisShot;
    }

    /** @param reference  the value the reference image establishes, or null when it shows nothing for it
     *  @param transition a change the shot/screenplay explicitly asks for, or null */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldAnalysis(VisualField field, String reference, Transition transition, List<Candidate> candidates) {
        public List<Candidate> candidates() {
            return candidates == null ? List.of() : candidates;
        }
    }

    /** @param evidence the exact words in the shot or screenplay that require the change */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transition(String value, String evidence) {}

    /** A prompt input's bearing on one field. The input's value is never taken from here -- it is read
     * from the prompt inputs themselves -- so a model cannot invent what a source said.
     *
     * @param compatibleRewrite the input's intent restated so it fits the reference (CONFLICT only) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(PromptInput input, Relation relation, String reason, String compatibleRewrite) {}

    public enum Relation {
        /** Agrees with the reference image. */
        COMPATIBLE,
        /** Differs, but both can hold in one frame (e.g. a saturated look within a muted scene). */
        TENSION,
        /** Cannot hold together with the reference (e.g. a different time of day). */
        CONFLICT
    }
}
