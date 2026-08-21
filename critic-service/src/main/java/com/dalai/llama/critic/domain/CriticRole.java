package com.dalai.llama.critic.domain;

/** One role per {@code ShotCritic} bean -- the roles the pre-flight harness runs in parallel
 * before any revision. */
public enum CriticRole {
    /** Level 0: deterministic, no LLM call -- see {@code HardConstraintCheck}. */
    HARD_CONSTRAINTS,
    DIRECTOR,
    DP,
    PRODUCTION_DESIGN,
    /** Level 4: deterministic, no LLM call -- see {@code GenerationFeasibilityCritic}. */
    GENERATION_FEASIBILITY
}
