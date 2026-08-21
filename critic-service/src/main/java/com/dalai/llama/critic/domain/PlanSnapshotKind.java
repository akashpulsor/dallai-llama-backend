package com.dalai.llama.critic.domain;

/** Every {@code CritiqueSession} has an ORIGINAL snapshot always, and a REVISED snapshot only
 * when the revision planner ran. */
public enum PlanSnapshotKind {
    ORIGINAL,
    REVISED
}
