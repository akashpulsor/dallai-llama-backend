package com.dalai.llama.critic.dto.idea;

/** Deliberately just two states, no auto-revision, no human-escalation step (unlike {@code
 * CritiqueVerdict}) -- an idea that FAILs isn't fixed in place, it's either regenerated wholesale
 * (creative-planning-service retries the whole batch if every candidate fails) or left visible
 * with its concerns shown so the creator can still pick it with eyes open. */
public enum IdeaCritiqueVerdict {
    PASS,
    FAIL
}
