package com.dalai.llama.creativeplanning.domain;

/** Deliberately two states, not a retry loop -- same discipline as critic-service's own {@code
 * CritiqueVerdict}: one bounded revision attempt, then either PASS or escalate to a human. */
public enum MarketingPlanCritiqueVerdict {
    PASS,
    NEEDS_HUMAN_REVIEW
}
