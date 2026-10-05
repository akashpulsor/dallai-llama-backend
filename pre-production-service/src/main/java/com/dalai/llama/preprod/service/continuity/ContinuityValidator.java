package com.dalai.llama.preprod.service.continuity;

import com.dalai.llama.preprod.service.continuity.ContinuityResolution.Competitor;
import com.dalai.llama.preprod.service.continuity.ContinuityResolution.VisualOverride;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Last check on a built step-shot prompt: every value the resolver replaced must be gone. The fix
 * itself happens before the prompt is written (the builder reads resolved inputs); this catches a
 * superseded value that still reaches the prompt through some other text -- the shot's action, a
 * camera note -- and reports it instead of shipping the contradiction silently.
 */
public final class ContinuityValidator {

    private ContinuityValidator() {}

    public static List<String> validate(String prompt, ContinuityResolution resolution) {
        List<String> warnings = new ArrayList<>();
        String haystack = ContinuityResolver.normalize(prompt);
        for (VisualOverride row : resolution.overrides()) {
            if (row.severity() != ResolutionSeverity.OVERRIDE) continue;
            for (Competitor superseded : row.superseded()) {
                if (resolution.substitutions().containsKey(superseded.input())
                        && containsPhrase(ContinuityResolver.normalize(resolution.substitutions().get(superseded.input()).value()),
                        ContinuityResolver.normalize(superseded.value()))) {
                    continue;
                }
                if (containsPhrase(haystack, ContinuityResolver.normalize(superseded.value()))) {
                    warnings.add(row.field().label() + ": \"" + superseded.value() + "\" (" + superseded.source().label()
                            + ") still appears in the prompt and may conflict with the previous shot's " + row.resolvedValue() + ".");
                }
            }
        }
        return warnings;
    }

    private static boolean containsPhrase(String haystack, String phrase) {
        if (phrase.isEmpty()) return false;
        return Pattern.compile("(^|\\W)" + Pattern.quote(phrase) + "($|\\W)").matcher(haystack).find();
    }
}
