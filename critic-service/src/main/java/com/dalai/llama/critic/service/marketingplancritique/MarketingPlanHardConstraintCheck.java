package com.dalai.llama.critic.service.marketingplancritique;

import com.dalai.llama.critic.dto.marketingplan.MarketingPlanContent;

import java.util.ArrayList;
import java.util.List;

/**
 * Level 0 of the marketing-plan critic hierarchy: deterministic, no LLM call. The six sections a
 * plan cannot be considered complete without -- executiveSummary, marketAnalysis,
 * positioningStatement, marketingObjectives, channelStrategy, budgetGuidance. Run first, before
 * any role critic, same reasoning as the shot-critique {@code HardConstraintCheck}: no point
 * spending three LLM calls critiquing a plan that's incomplete on its face. Findings from here
 * are always P1 (blocking) by construction.
 */
final class MarketingPlanHardConstraintCheck {

    private MarketingPlanHardConstraintCheck() {
    }

    static List<MarketingPlanCriticFindingItem> run(MarketingPlanContent content) {
        List<MarketingPlanCriticFindingItem> findings = new ArrayList<>();

        requireSection(findings, content.executiveSummary(), "executiveSummary");
        requireSection(findings, content.marketAnalysis(), "marketAnalysis");
        requireSection(findings, content.positioningStatement(), "positioningStatement");
        requireSection(findings, content.marketingObjectives(), "marketingObjectives");
        requireSection(findings, content.channelStrategy(), "channelStrategy");
        requireSection(findings, content.budgetGuidance(), "budgetGuidance");

        return findings;
    }

    private static void requireSection(List<MarketingPlanCriticFindingItem> findings, String value, String sectionName) {
        if (value == null || value.isBlank()) {
            findings.add(new MarketingPlanCriticFindingItem(
                    "Section '" + sectionName + "' is missing or blank.",
                    "A plan with this section empty is not usable as delivered -- the client would receive an incomplete document.",
                    sectionName + " was null/blank in the generated plan content.",
                    "Regenerate or fill in the " + sectionName + " section before finalizing.",
                    "P1"));
        }
    }
}
