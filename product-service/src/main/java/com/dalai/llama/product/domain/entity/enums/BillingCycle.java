package com.dalai.llama.product.domain.entity.enums;

/** How often a plan renews. Null on a {@code Plan}/{@code Subscription} row means "not modeled for
 * this product" (every PBX plan today is implicitly monthly via its recurring charge cadence) --
 * only newly-created plans (e.g. creator-video) set this explicitly. */
public enum BillingCycle {
    MONTHLY,
    QUARTERLY,
    YEARLY
}
