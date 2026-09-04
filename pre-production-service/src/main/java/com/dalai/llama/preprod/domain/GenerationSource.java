package com.dalai.llama.preprod.domain;

/** Where one version of a versioned generation artifact (Screenplay, Script) came from -- a
 * plain LLM generation call, a creator's saved manual edit of an earlier version (see {@code
 * parent_id}, only ever set for EDITED rows), or an LLM generation that only came out this way
 * because an internal critic pass rejected an earlier attempt and forced a revision (see {@code
 * ScriptGenerationService#generate}'s critique-retry loop) -- distinct from GENERATED so a
 * version-history UI can show "the critic made it rewrite this" rather than treating every
 * automated attempt the same. Same convention as creative-planning-service's {@code
 * IdeaOptionSource}. */
public enum GenerationSource {
    GENERATED,
    EDITED,
    CRITIC
}
