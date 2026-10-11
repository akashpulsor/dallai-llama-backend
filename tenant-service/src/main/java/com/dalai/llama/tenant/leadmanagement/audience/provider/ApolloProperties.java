package com.dalai.llama.tenant.leadmanagement.audience.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Apollo.io, the people directory (rule 44), bound from {@code audience-provider.apollo.*}. People
 * search is free; revealing a person's email costs a credit (free accounts get a monthly allowance). */
@ConfigurationProperties(prefix = "audience-provider.apollo")
public record ApolloProperties(
        String apiKey,
        String baseUrl,
        /* Credits we spend per calendar month; set to the plan's allowance. */
        int monthlyCredits,
        int searchCacheDays,
        int perPage,
        /* Who brands' decision makers are, when the creator doesn't say. */
        List<String> defaultTitles
) {
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
