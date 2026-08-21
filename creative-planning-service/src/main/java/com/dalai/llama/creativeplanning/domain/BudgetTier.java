package com.dalai.llama.creativeplanning.domain;

/** Carried onto {@code LockedIdea} and, from there, into pre-production-service's own
 * {@code Project.budgetTier} -- chosen per campaign session, not fixed per brand. */
public enum BudgetTier {
    LEAN,
    STANDARD,
    PREMIUM
}
