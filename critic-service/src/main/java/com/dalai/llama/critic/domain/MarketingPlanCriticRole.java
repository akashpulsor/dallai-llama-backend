package com.dalai.llama.critic.domain;

public enum MarketingPlanCriticRole {
    /** Level 0: deterministic, no LLM call -- required sections present and non-blank. */
    HARD_CONSTRAINTS,
    /** Internal coherence: does positioning follow from the market analysis, do objectives
     * follow from the strategy -- and does referencedCaseStudyPatterns actually name specific
     * real companies/campaigns rather than going vague on generic framework names. */
    STRATEGY,
    /** Is this plan actually specific to the stated audience, or generic boilerplate that could
     * apply to any brand. */
    AUDIENCE_FIT,
    /** Is the plan realistic for the stated budget tier. */
    FEASIBILITY
}
