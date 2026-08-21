package com.dalai.llama.creativeplanning.domain;

public enum MarketingPlanCriticRole {
    /** Level 0: deterministic, no LLM call -- required sections present and non-blank. */
    HARD_CONSTRAINTS,
    /** Internal coherence: does positioning follow from the market analysis, do objectives
     * follow from the strategy, does the plan hang together as one argument. */
    STRATEGY,
    /** Is this plan actually specific to the stated audience, or generic boilerplate that could
     * apply to any brand. */
    AUDIENCE_FIT,
    /** Is the plan realistic for the stated budget tier -- not proposing a national TV campaign
     * for a LEAN-tier bootstrapped brand. */
    FEASIBILITY
}
