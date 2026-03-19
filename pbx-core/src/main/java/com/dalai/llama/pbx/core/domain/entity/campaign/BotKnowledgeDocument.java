package com.dalai.llama.pbx.core.domain.entity.campaign;


import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * RAG knowledge document for a bot.
 *
 * Admin uploads documents (FAQ, product info, scripts, policies).
 * voice-brain stuffs these into LLM system prompt for grounded answers.
 *
 * MVP: Full content injected into context window (works for <50 docs, <100K tokens).
 * Future: Embed → vector DB → retrieve top-K relevant chunks per utterance.
 */
@Entity
@Table(name = "bot_knowledge_documents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BotKnowledgeDocument {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_type", nullable = false, length = 30)
    @Builder.Default
    private String contentType = "FAQ";  // FAQ, PRODUCT, SCRIPT, POLICY, CUSTOM

    @Column(length = 10)
    @Builder.Default
    private String language = "en";

    @Column(name = "embedding_status", length = 20)
    @Builder.Default
    private String embeddingStatus = "NONE";  // NONE, PENDING, INDEXED, FAILED

    @Column(name = "chunk_count")
    @Builder.Default
    private Integer chunkCount = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}