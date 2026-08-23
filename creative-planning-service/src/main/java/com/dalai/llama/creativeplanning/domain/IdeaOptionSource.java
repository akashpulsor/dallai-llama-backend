package com.dalai.llama.creativeplanning.domain;

/** Where one {@code IdeaOption} row came from -- an LLM generation batch, or a creator's saved
 * edit of an earlier option (see {@code idea_option.parent_id}, which is only ever set for
 * EDITED rows). */
public enum IdeaOptionSource {
    GENERATED,
    EDITED
}
