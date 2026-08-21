package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.domain.CriticRole;
import com.dalai.llama.critic.dto.shotcontext.ShotContext;

import java.util.List;
import java.util.UUID;

/**
 * One role's pre-flight review of a shot plan -- {@code CritiqueOrchestrator} runs every
 * registered {@code ShotCritic} bean, so adding a new critic role (e.g. a future VFX critic) is a
 * new bean, not a new branch anywhere.
 */
public interface ShotCritic {

    CriticRole role();

    List<CriticFindingItem> critique(UUID tenantId, ShotContext plan);
}
