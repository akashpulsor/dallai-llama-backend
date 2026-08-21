package com.dalai.llama.critic.dto.marketingplan;

/** Inbound/outbound wire-contract copy of creative-planning-service's own {@code
 * MarketingPlanContent} -- this is what the harness actually critiques and revises. Same
 * convention as {@code ShotContext} being mirrored between pre-production-service and this
 * service: each side owns its copy of the contract it depends on. */
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
