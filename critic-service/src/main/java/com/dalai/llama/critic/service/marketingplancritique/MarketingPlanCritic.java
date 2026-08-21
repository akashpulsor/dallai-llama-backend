package com.dalai.llama.critic.service.marketingplancritique;

import com.dalai.llama.critic.domain.MarketingPlanCriticRole;
import com.dalai.llama.critic.dto.marketingplan.MarketingPlanContent;

import java.util.List;
import java.util.UUID;

/**
 * One role's pre-flight review of a marketing plan -- {@code MarketingPlanCritiqueOrchestrator}
 * runs every registered {@code MarketingPlanCritic} bean, so adding a new role is a new bean, not
 * a new branch anywhere. Marketing-plan sibling of {@code ShotCritic}.
 */
public interface MarketingPlanCritic {

    MarketingPlanCriticRole role();

    List<MarketingPlanCriticFindingItem> critique(UUID tenantId, MarketingPlanContent content, String brandAndAudienceContext);
}
