package com.dalai.llama.preprod.domain;

/** Shared by SCRIPT and SCREENPLAY -- both are single-document drafts finalized once, not a
 * per-service bespoke status pair. */
public enum DraftStatus {
    DRAFT,
    FINALIZED
}
