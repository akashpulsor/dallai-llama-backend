package com.dalai.llama.pbx.core.repository.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.BotEscalationIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BotEscalationIntentRepository extends JpaRepository<BotEscalationIntent, UUID> {

    List<BotEscalationIntent> findByBotIdAndEnabledTrueOrderByPriorityDesc(UUID botId);

    List<BotEscalationIntent> findByTenantId(UUID tenantId);

    void deleteByBotId(UUID botId);
}