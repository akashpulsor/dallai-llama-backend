package com.dalai.llama.tenant.showcase.ranking;

import java.util.UUID;

/** How many of a creator's brand requests became briefs (an L4 signal). Implemented by the
 * lead-management inquiry module, so the ranking doesn't depend on it directly. */
public interface ConvertedRequestCounter {

    int convertedRequests(UUID tenantId);
}
