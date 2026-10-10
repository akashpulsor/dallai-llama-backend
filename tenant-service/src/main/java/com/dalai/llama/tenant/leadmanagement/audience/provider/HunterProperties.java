package com.dalai.llama.tenant.leadmanagement.audience.provider;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/** Hunter.io, the brand-directory provider (rule 42), bound from {@code audience-provider.hunter.*}.
 * Company search (Discover) is free; finding a company's emails (Domain Search) costs credits
 * (the free plan has 50 a month), so each company is looked up once and shared. */
@ConfigurationProperties(prefix = "audience-provider.hunter")
public record HunterProperties(
        String apiKey,
        String baseUrl,
        /* Credits we allow ourselves per calendar month; set to the plan's allowance. */
        int monthlyCredits,
        /* Emails asked for per company (each found email costs a credit). */
        int emailsPerCompany,
        int searchCacheDays,
        int contactsCacheDays,
        /* Our industries → Hunter's industry names (hunter.io/files/industries.json). */
        Map<ShowcaseIndustry, List<String>> industries
) {
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
