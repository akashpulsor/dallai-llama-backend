package com.dalai.llama.preprod.dto;

/** Body for {@code POST /v1/projects/{id}/screenplay/generate} -- previously took no body at all,
 * so the LLM had no language guidance and would emit dialogue in whatever script the model felt
 * like (Devanagari for a project whose stored dialogue language was hi-Latn-IN, in the incident
 * that motivated this change). {@code dialogueLanguage} is a BCP-47 code that overrides the
 * project's saved default AND becomes the new saved default, matching {@link
 * GenerateScriptRequest}'s contract so a creator picking a language on the screenplay tab doesn't
 * have to also touch project settings. Null means "use ProjectConfig.dialogueLanguage as-is". */
public record GenerateScreenplayRequest(
        String dialogueLanguage
) {
}
