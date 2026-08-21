package com.dalai.llama.critic.service.marketingplancritique;

/** Flat, revision-prompt-facing view of one finding -- role is folded in since the revision
 * planner needs to know who raised each point. Marketing-plan sibling of {@code
 * FindingForRevision}. */
public record MarketingPlanFindingForRevision(
        String role,
        String observation,
        String risk,
        String cause,
        String correction,
        String severity
) {
}
