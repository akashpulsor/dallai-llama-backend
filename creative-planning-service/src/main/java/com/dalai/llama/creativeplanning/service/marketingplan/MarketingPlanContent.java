package com.dalai.llama.creativeplanning.service.marketingplan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The plan's actual content, in the shape both MARKETING_PLAN_GENERATION and
 * MARKETING_PLAN_REVISION return -- one shared parse target for both, since a revision is just a
 * new version of the same eleven sections. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketingPlanContent(
        String executiveSummary,
        String marketAnalysis,
        String targetAudienceProfile,
        String positioningStatement,
        String brandStrategy,
        String marketingObjectives,
        String channelStrategy,
        String contentStrategy,
        String budgetGuidance,
        String successMetrics,
        String risksAndMitigations,
        String referencedCaseStudyPatterns
) {
}
