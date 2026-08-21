package com.dalai.llama.creativeplanning.service.marketingplan;

import com.dalai.llama.creativeplanning.domain.entity.MarketingPlan;
import com.dalai.llama.creativeplanning.dto.MarketingPlanView;

/** The only place {@link MarketingPlanContent} (an LLM response) is applied onto the persisted
 * {@link MarketingPlan} entity, or the entity is turned into its view -- used by both initial
 * generation and revision, so the mapping is written once. */
final class MarketingPlanContentMapper {

    private MarketingPlanContentMapper() {
    }

    static void applyTo(MarketingPlan plan, MarketingPlanContent content) {
        plan.setExecutiveSummary(content.executiveSummary());
        plan.setMarketAnalysis(content.marketAnalysis());
        plan.setTargetAudienceProfile(content.targetAudienceProfile());
        plan.setPositioningStatement(content.positioningStatement());
        plan.setBrandStrategy(content.brandStrategy());
        plan.setMarketingObjectives(content.marketingObjectives());
        plan.setChannelStrategy(content.channelStrategy());
        plan.setContentStrategy(content.contentStrategy());
        plan.setBudgetGuidance(content.budgetGuidance());
        plan.setSuccessMetrics(content.successMetrics());
        plan.setRisksAndMitigations(content.risksAndMitigations());
        plan.setReferencedCaseStudyPatterns(content.referencedCaseStudyPatterns());
    }

    static MarketingPlanContent fromEntity(MarketingPlan plan) {
        return new MarketingPlanContent(plan.getExecutiveSummary(), plan.getMarketAnalysis(), plan.getTargetAudienceProfile(),
                plan.getPositioningStatement(), plan.getBrandStrategy(), plan.getMarketingObjectives(), plan.getChannelStrategy(),
                plan.getContentStrategy(), plan.getBudgetGuidance(), plan.getSuccessMetrics(), plan.getRisksAndMitigations(),
                plan.getReferencedCaseStudyPatterns());
    }

    /** Flattens a plan into one embeddable text blob for {@code ChatServiceClient} -- section
     * labels included so retrieval snippets read sensibly out of context in a chat reply. */
    static String toEmbeddingText(MarketingPlan plan) {
        return "Executive Summary: " + orEmpty(plan.getExecutiveSummary())
                + "\nMarket Analysis: " + orEmpty(plan.getMarketAnalysis())
                + "\nTarget Audience Profile: " + orEmpty(plan.getTargetAudienceProfile())
                + "\nPositioning Statement: " + orEmpty(plan.getPositioningStatement())
                + "\nBrand Strategy: " + orEmpty(plan.getBrandStrategy())
                + "\nMarketing Objectives: " + orEmpty(plan.getMarketingObjectives())
                + "\nChannel Strategy: " + orEmpty(plan.getChannelStrategy())
                + "\nContent Strategy: " + orEmpty(plan.getContentStrategy())
                + "\nBudget Guidance: " + orEmpty(plan.getBudgetGuidance())
                + "\nSuccess Metrics: " + orEmpty(plan.getSuccessMetrics())
                + "\nRisks and Mitigations: " + orEmpty(plan.getRisksAndMitigations())
                + "\nCase Studies Referenced: " + orEmpty(plan.getReferencedCaseStudyPatterns());
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    static MarketingPlanView toView(MarketingPlan plan) {
        return new MarketingPlanView(plan.getId(), plan.getBrandContextId(), plan.getProductProfileId(),
                plan.getTargetAudienceInput(), plan.getBudgetTier(), plan.getStatus(),
                plan.getExecutiveSummary(), plan.getMarketAnalysis(), plan.getTargetAudienceProfile(),
                plan.getPositioningStatement(), plan.getBrandStrategy(), plan.getMarketingObjectives(),
                plan.getChannelStrategy(), plan.getContentStrategy(), plan.getBudgetGuidance(),
                plan.getSuccessMetrics(), plan.getRisksAndMitigations(), plan.getReferencedCaseStudyPatterns(),
                plan.getCreatedAt());
    }
}
