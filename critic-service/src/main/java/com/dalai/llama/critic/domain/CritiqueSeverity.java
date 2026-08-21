package com.dalai.llama.critic.domain;

/** P1 = blocking (must be resolved before generation); P2/P3 = advisory, surfaced but don't gate
 * the validation gate on their own. */
public enum CritiqueSeverity {
    P1,
    P2,
    P3
}
