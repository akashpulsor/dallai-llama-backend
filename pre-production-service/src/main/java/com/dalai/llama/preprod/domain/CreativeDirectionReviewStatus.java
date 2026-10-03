package com.dalai.llama.preprod.domain;

/**
 * Where one director's treatment stands in review. The AI recommendation is not a status: a
 * recommended treatment starts PROPOSED like the others and only becomes APPROVED when a person
 * approves it.
 * <ul>
 *   <li>PROPOSED -- generated (or revised) and not yet acted on.</li>
 *   <li>SELECTED -- the treatment the reviewer is currently looking at; at most one per project.</li>
 *   <li>REVISION_REQUESTED -- feedback asks for changes; the next revision supersedes it.</li>
 *   <li>APPROVED -- the project's creative contract; at most one per project, never edited.</li>
 *   <li>SUPERSEDED -- replaced by a revision, a newer generation, or a newer approval. Kept.</li>
 * </ul>
 */
public enum CreativeDirectionReviewStatus {
    PROPOSED,
    SELECTED,
    REVISION_REQUESTED,
    APPROVED,
    SUPERSEDED
}
