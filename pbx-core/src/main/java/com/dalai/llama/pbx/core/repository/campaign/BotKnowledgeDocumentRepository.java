package com.dalai.llama.pbx.core.repository.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.BotKnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BotKnowledgeDocumentRepository extends JpaRepository<BotKnowledgeDocument, UUID> {

    List<BotKnowledgeDocument> findByBotIdAndEnabledTrueOrderByDisplayOrder(UUID botId);

    List<BotKnowledgeDocument> findByBotIdAndContentTypeAndEnabledTrue(UUID botId, String contentType);

    List<BotKnowledgeDocument> findByTenantId(UUID tenantId);

    long countByBotId(UUID botId);

    void deleteByBotId(UUID botId);
}