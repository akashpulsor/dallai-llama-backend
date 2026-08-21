package com.dalai.llama.postprod.domain;

/** PROJECT: every completed shot in the project gets its own post-production job, created
 * together as a batch. SHOT: a single shot, independent of whether siblings are done yet -- both
 * paths a user can take, per the explicit ask: move the whole finished video into post-production
 * at once, or work a single shot on its own. */
public enum PostProductionScope {
    PROJECT,
    SHOT
}
