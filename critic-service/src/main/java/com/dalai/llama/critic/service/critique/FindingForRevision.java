package com.dalai.llama.critic.service.critique;

/** Flat, revision-prompt-facing view of one finding -- role is folded in since the revision
 * planner needs to know who raised each point, unlike the persisted {@code CritiqueFinding} row
 * which carries role as its own column. */
public record FindingForRevision(
        String role,
        String observation,
        String risk,
        String cause,
        String correction,
        String severity
) {
}
