package com.dalai.llama.tenant.youtube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** YouTube integration settings, bound from {@code youtube.*}. The API key comes from a
 * Kubernetes secret via env var; when it is blank every YouTube call is refused with a clear
 * message instead of a 403 from Google. */
@ConfigurationProperties(prefix = "youtube")
public record YouTubeProperties(
        String apiKey,
        /* Data API root; overridden in tests to point at a local stub. */
        String baseUrl,
        /* Most uploads read from one channel on import. */
        int importMaxVideos,
        /* Cached API data older than this is refreshed (policy limit is 30 days). */
        int refreshAfterDays,
        /* Videos refreshed per scheduled run, in batches of 50 per API call. */
        int refreshBatchSize,
        Eligibility eligibility
) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public record Eligibility(
            int minDurationSeconds,
            int maxDurationSeconds
    ) {
    }
}
