package com.dalai.llama.preprod.dto;

/** Body for {@code POST /v1/projects/{id}/screenplay/generate} -- previously took no body at all,
 * so the LLM had no language guidance and would emit dialogue in whatever script the model felt
 * like (Devanagari for a project whose stored dialogue language was hi-Latn-IN, in the incident
 * that motivated this change). {@code dialogueLanguage} is a BCP-47 code that overrides the
 * project's saved default AND becomes the new saved default, matching {@link
 * GenerateScriptRequest}'s contract so a creator picking a language on the screenplay tab doesn't
 * have to also touch project settings. Null means "use ProjectConfig.dialogueLanguage as-is".
 * <p>
 * {@code scriptVersion} lets the creator pick which script snapshot to base the screenplay on
 * -- Script is versioned (every generate/regenerate/saveEdit inserts a new row) but by default
 * screenplay generation reads the live/latest row, so a creator viewing an older version had no
 * way to say "use v2 for screenplay". When set, the service loads that specific ScriptVersion's
 * text instead. Null means "use the live/latest row".
 * <p>
 * {@code narrativeLanguage} is the language the SCRIPT prose (scriptText, summary, emotionalArc,
 * etc.) is written in -- separate from {@code dialogueLanguage} which controls only spoken
 * dialogue. A common Indian-market pattern is English prose (creator-readable) with Hindi
 * dialogue lines. Null defaults to en-US. */
public record GenerateScreenplayRequest(
        String dialogueLanguage,
        String narrativeLanguage,
        Integer scriptVersion
) {
}
