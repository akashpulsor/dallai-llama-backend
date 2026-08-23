package com.dalai.llama.preprod.domain;

/** Where one version of a versioned generation artifact (currently just Screenplay) came from --
 * an LLM generation call, or a creator's saved manual edit of an earlier version (see
 * {@code parent_id}, which is only ever set for EDITED rows). Same convention as
 * creative-planning-service's {@code IdeaOptionSource}. */
public enum GenerationSource {
    GENERATED,
    EDITED
}
