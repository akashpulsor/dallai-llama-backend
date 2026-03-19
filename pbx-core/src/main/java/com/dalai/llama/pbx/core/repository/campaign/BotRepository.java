package com.dalai.llama.pbx.core.repository.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.Bot;
import com.dalai.llama.pbx.core.domain.enums.BotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bot table — AI conversation configuration (reusable across campaigns).
 *
 * Read paths:
 *   - AiConfigController /internal/ai/config/{tenantId} → voice-brain fetches bot config
 *     at call start to initialize Pipecat pipeline (system prompt, voice, escalation rules)
 *   - CampaignService → assign bot to campaign
 *   - DialerEngine → fetch bot config for outbound AI calls
 *
 * Write paths:
 *   - BotController CRUD
 */
@Repository
public interface BotRepository extends JpaRepository<Bot, UUID> {

    List<Bot> findByTenantId(UUID tenantId);

    List<Bot> findByTenantIdAndStatus(UUID tenantId, BotStatus status);

    Optional<Bot> findByTenantIdAndName(UUID tenantId, String name);

    boolean existsByTenantIdAndName(UUID tenantId, String name);

    long countByTenantId(UUID tenantId);

    /**
     * Find first active bot for a tenant — used as default bot when
     * AiConfigController is called without a specific botId.
     */
    Optional<Bot> findFirstByTenantIdAndStatusOrderByUpdatedAtDesc(UUID tenantId, BotStatus status);
}