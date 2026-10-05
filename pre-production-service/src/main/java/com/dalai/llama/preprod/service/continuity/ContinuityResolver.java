package com.dalai.llama.preprod.service.continuity;

import com.dalai.llama.preprod.service.continuity.ContinuityAnalysis.Candidate;
import com.dalai.llama.preprod.service.continuity.ContinuityAnalysis.FieldAnalysis;
import com.dalai.llama.preprod.service.continuity.ContinuityAnalysis.Relation;
import com.dalai.llama.preprod.service.continuity.ContinuityAnalysis.Transition;
import com.dalai.llama.preprod.service.continuity.ContinuityResolution.Competitor;
import com.dalai.llama.preprod.service.continuity.ContinuityResolution.ResolvedValue;
import com.dalai.llama.preprod.service.continuity.ContinuityResolution.Substitution;
import com.dalai.llama.preprod.service.continuity.ContinuityResolution.VisualOverride;
import com.dalai.llama.preprod.service.generation.PromptInput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides every visual field of a step shot from the analysis, in one fixed order of authority:
 * the user's choice, then a change the shot or screenplay explicitly asks for (its quoted words must
 * really be in that text), then the reference image, then project look, shot metadata and lighting
 * plan by {@link ContinuitySource#rank}. "Preserve by default, change only by explicit instruction."
 *
 * <p>Pure and deterministic: the model only reports facts (what the image shows, what each input
 * says, what the text asks for); which value wins is decided here, and every decision keeps its
 * provenance.
 */
public final class ContinuityResolver {

    private ContinuityResolver() {}

    /** The text an explicit transition may quote: the new shot's own words, and the screenplay
     * scene's words when the shot opens a different scene from the reference shot's (a scene of its
     * own can't transition within itself). */
    public record Evidence(String shotDescription, String screenplay) {

        ContinuitySource sourceOf(String quote) {
            String needle = normalize(quote);
            if (needle.isEmpty()) return null;
            if (normalize(shotDescription).contains(needle)) return ContinuitySource.SHOT_DESCRIPTION;
            if (normalize(screenplay).contains(needle)) return ContinuitySource.SCREENPLAY;
            return null;
        }
    }

    public static ContinuityResolution resolve(ContinuityAnalysis analysis, Map<PromptInput, String> rawInputs,
                                               Map<VisualField, String> userOverrides, Evidence evidence) {
        Map<VisualField, ResolvedValue> state = new EnumMap<>(VisualField.class);
        List<VisualOverride> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<PromptInput, Substitution> substitutions = new EnumMap<>(PromptInput.class);

        Map<VisualField, FieldAnalysis> byField = new EnumMap<>(VisualField.class);
        for (FieldAnalysis field : analysis.fields()) {
            if (field != null && field.field() != null) byField.putIfAbsent(field.field(), field);
        }
        for (VisualField field : userOverrides.keySet()) {
            byField.putIfAbsent(field, new FieldAnalysis(field, null, null, List.of()));
        }

        for (FieldAnalysis analysed : byField.values()) {
            VisualField field = analysed.field();
            String reference = blankToNull(analysed.reference());
            Map<PromptInput, Candidate> candidates = candidatesWithValues(analysed, rawInputs);
            List<Competitor> competing = candidates.keySet().stream()
                    .map(input -> new Competitor(input, input.source(), rawInputs.get(input))).toList();

            String chosen = blankToNull(userOverrides.get(field));
            if (chosen != null) {
                state.put(field, new ResolvedValue(chosen, ContinuitySource.USER_OVERRIDE, ResolutionSeverity.USER_OVERRIDE,
                        "You chose this value.", competing));
                String original = reference != null ? reference : strongest(competing).map(Competitor::value).orElse(null);
                rows.add(new VisualOverride(field, original, chosen,
                        reference != null ? ContinuitySource.REFERENCE_IMAGE : strongest(competing).map(Competitor::source).orElse(null),
                        ContinuitySource.USER_OVERRIDE,
                        reference != null && !same(reference, chosen)
                                ? "You chose " + chosen + " over the " + reference + " the previous shot establishes -- continuity is deliberately broken here."
                                : "You chose " + chosen + ".",
                        ResolutionSeverity.USER_OVERRIDE, true, competing));
                substituteCanonical(field, chosen, rawInputs, substitutions);
                continue;
            }

            Transition transition = analysed.transition();
            if (transition != null && blankToNull(transition.value()) != null) {
                ContinuitySource evidenceSource = evidence.sourceOf(transition.evidence());
                if (evidenceSource != null) {
                    state.put(field, new ResolvedValue(transition.value(), evidenceSource, ResolutionSeverity.EXPLICIT_TRANSITION,
                            explicitReason(transition), competing));
                    String original = reference != null ? reference : strongest(competing).map(Competitor::value).orElse(null);
                    rows.add(new VisualOverride(field, original, transition.value(),
                            reference != null ? ContinuitySource.REFERENCE_IMAGE : strongest(competing).map(Competitor::source).orElse(null),
                            evidenceSource, explicitReason(transition), ResolutionSeverity.EXPLICIT_TRANSITION, true, List.of()));
                    substituteCanonical(field, transition.value(), rawInputs, substitutions);
                    continue;
                }
                warnings.add(field.label() + ": ignored a change to \"" + transition.value() + "\" -- the words \""
                        + transition.evidence() + "\" are not in this shot's description or screenplay, so the previous shot's "
                        + field.label().toLowerCase() + " is kept.");
            }

            if (reference == null) {
                strongest(competing).ifPresent(top -> state.put(field, new ResolvedValue(top.value(), top.source(),
                        ResolutionSeverity.APPLIED, "The previous shot establishes nothing here, so the shot's own value applies.", competing)));
                continue;
            }

            List<Competitor> conflicts = withRelation(candidates, competing, Relation.CONFLICT);
            List<Competitor> tensions = withRelation(candidates, competing, Relation.TENSION);
            for (Competitor conflict : conflicts) {
                String rewrite = blankToNull(candidates.get(conflict.input()).compatibleRewrite());
                substitutions.putIfAbsent(conflict.input(), new Substitution(
                        rewrite != null ? rewrite : isCanonical(conflict.input(), field) ? reference : null, field));
            }
            ResolutionSeverity severity = !conflicts.isEmpty() ? ResolutionSeverity.OVERRIDE
                    : !tensions.isEmpty() ? ResolutionSeverity.WARNING : ResolutionSeverity.PRESERVED;
            state.put(field, new ResolvedValue(reference, ContinuitySource.REFERENCE_IMAGE, severity,
                    "Established by the previous shot.", competing));
            if (!conflicts.isEmpty()) {
                Competitor top = strongest(conflicts).orElseThrow();
                rows.add(new VisualOverride(field, top.value(), reference, top.source(), ContinuitySource.REFERENCE_IMAGE,
                        reasonOr(candidates.get(top.input()), "This shot does not ask for a different " + field.label().toLowerCase()
                                + ", so the previous shot's " + reference + " is kept."),
                        ResolutionSeverity.OVERRIDE, true, conflicts));
            }
            if (!tensions.isEmpty()) {
                Competitor top = strongest(tensions).orElseThrow();
                rows.add(new VisualOverride(field, top.value(), reference, top.source(), ContinuitySource.REFERENCE_IMAGE,
                        reasonOr(candidates.get(top.input()), "This differs from the previous shot but can coexist with it, so nothing was replaced."),
                        ResolutionSeverity.WARNING, true, tensions));
            }
        }

        rows.sort(Comparator.comparing(VisualOverride::field));
        return new ContinuityResolution(state, List.copyOf(rows), List.copyOf(warnings), analysis.changesForThisShot(),
                blankToNull(analysis.newElementLighting()), substitutions);
    }

    /** The input that states a field directly (the shot's own time-of-day for TIME_OF_DAY) takes the
     * winning value itself, so a changed or kept field reads the same everywhere in the prompt. */
    static boolean isCanonical(PromptInput input, VisualField field) {
        return switch (input) {
            case SHOT_TIME_OF_DAY -> field == VisualField.TIME_OF_DAY;
            case SHOT_LIGHTING_MOOD -> field == VisualField.LIGHTING;
            case SHOT_LOCATION -> field == VisualField.LOCATION;
            default -> false;
        };
    }

    private static void substituteCanonical(VisualField field, String value, Map<PromptInput, String> rawInputs,
                                            Map<PromptInput, Substitution> substitutions) {
        for (PromptInput input : rawInputs.keySet()) {
            if (isCanonical(input, field) && !same(rawInputs.get(input), value)) {
                substitutions.put(input, new Substitution(value, field));
            }
        }
    }

    /** The field's candidates whose input actually has a value, first mention of an input wins. */
    private static Map<PromptInput, Candidate> candidatesWithValues(FieldAnalysis field, Map<PromptInput, String> rawInputs) {
        Map<PromptInput, Candidate> out = new LinkedHashMap<>();
        for (Candidate candidate : field.candidates()) {
            if (candidate != null && candidate.input() != null && rawInputs.containsKey(candidate.input())) {
                out.putIfAbsent(candidate.input(), candidate);
            }
        }
        return out;
    }

    private static List<Competitor> withRelation(Map<PromptInput, Candidate> candidates, List<Competitor> competing, Relation relation) {
        return competing.stream().filter(c -> candidates.get(c.input()).relation() == relation).toList();
    }

    private static java.util.Optional<Competitor> strongest(List<Competitor> competitors) {
        return competitors.stream().min(Comparator.comparingInt(c -> c.source().rank()));
    }

    private static String explicitReason(Transition transition) {
        return "This shot explicitly asks for it: \"" + transition.evidence().trim() + "\".";
    }

    private static String reasonOr(Candidate candidate, String fallback) {
        String reason = candidate == null ? null : blankToNull(candidate.reason());
        return reason != null ? reason : fallback;
    }

    static boolean same(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase().replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String blankToNull(String value) {
        return PromptInput.isEmpty(value) ? null : value.trim();
    }
}
