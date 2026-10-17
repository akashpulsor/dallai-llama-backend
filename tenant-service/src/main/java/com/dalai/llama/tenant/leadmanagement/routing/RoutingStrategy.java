package com.dalai.llama.tenant.leadmanagement.routing;

/** How a request sent to Dalaillama (not to a named creator) finds its creator. */
public enum RoutingStrategy {
    /** Straight to the active creator who covers the industry and has waited longest; a decline
     * moves it on to the next. */
    ROUND_ROBIN_BY_INDUSTRY,
    /** Offered to every active creator; the first to accept takes it. */
    OFFER_TO_ALL,
    /** Waits for ops to pick the creator. */
    OPS_ASSIGNS
}
