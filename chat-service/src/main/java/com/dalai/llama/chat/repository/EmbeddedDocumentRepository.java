package com.dalai.llama.chat.repository;

import com.dalai.llama.chat.domain.entity.EmbeddedDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmbeddedDocumentRepository extends JpaRepository<EmbeddedDocument, UUID> {

    Optional<EmbeddedDocument> findByTenantIdAndSourceServiceAndSourceIdAndKind(
            UUID tenantId, String sourceService, String sourceId, String kind);

    List<EmbeddedDocument> findByTenantIdAndEmbeddingIsNotNull(UUID tenantId);

    List<EmbeddedDocument> findByTenantIdAndScopeIdAndEmbeddingIsNotNull(UUID tenantId, UUID scopeId);
}
