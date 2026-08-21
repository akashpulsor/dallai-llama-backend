package com.dalai.llama.critic.domain;

/** Deliberately two states, not a retry loop: PASS (no blocking findings, or the one bounded
 * revision pass resolved them) or NEEDS_HUMAN_REVIEW (a P1 finding survived the revision pass --
 * escalate to a person, never auto-retry indefinitely). */
public enum CritiqueVerdict {
    PASS,
    NEEDS_HUMAN_REVIEW
}
