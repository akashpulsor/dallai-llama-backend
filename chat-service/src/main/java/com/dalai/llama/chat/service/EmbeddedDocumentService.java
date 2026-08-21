package com.dalai.llama.chat.service;

import com.dalai.llama.chat.domain.entity.EmbeddedDocument;
import com.dalai.llama.chat.dto.IngestDocumentRequest;
import com.dalai.llama.chat.repository.EmbeddedDocumentRepository;
import com.dalai.llama.chat.service.llmgateway.EmbeddingParser;
import com.dalai.llama.chat.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The central embedding index -- ingestion (write) and retrieval (read) sides of the "everything
 * that happened to a project, chattable" memory layer, see {@link
 * com.dalai.llama.chat.domain.entity.EmbeddedDocument}'s javadoc for the architecture this
 * closes. Cosine ranking runs in application code, not an indexed vector search, matching the
 * same constraint and tradeoff already accepted in critic-service's {@code SimilarFeedbackService}
 * (no pgvector on this cluster's shared Postgres).
 */
@Service
public class EmbeddedDocumentService {

    private final EmbeddedDocumentRepository embeddedDocumentRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String embeddingModel;

    public EmbeddedDocumentService(
            EmbeddedDocumentRepository embeddedDocumentRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${chat.llm-gateway.default-embedding-model}") String embeddingModel
    ) {
        this.embeddedDocumentRepository = embeddedDocumentRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
    }

    @Transactional
    public void ingest(UUID tenantId, IngestDocumentRequest request) {
        String raw = llmGatewayClient.embed(tenantId.toString(), embeddingModel, request.content());
        double[] embedding = EmbeddingParser.parse(objectMapper, raw);

        EmbeddedDocument document = embeddedDocumentRepository
                .findByTenantIdAndSourceServiceAndSourceIdAndKind(tenantId, request.sourceService(), request.sourceId(), request.kind())
                .orElseGet(() -> EmbeddedDocument.builder()
                        .tenantId(tenantId)
                        .sourceService(request.sourceService())
                        .sourceId(request.sourceId())
                        .kind(request.kind())
                        .createdAt(OffsetDateTime.now())
                        .build());
        document.setScopeId(request.scopeId());
        document.setContent(request.content());
        document.setEmbedding(embedding);
        document.setUpdatedAt(OffsetDateTime.now());
        embeddedDocumentRepository.save(document);
    }

    /** Scoped to {@code scopeId} when given (e.g. "everything about this marketing plan"),
     * otherwise ranks across every embedded document the tenant has. */
    @Transactional(readOnly = true)
    public List<EmbeddedDocument> findSimilar(UUID tenantId, UUID scopeId, String queryText, int limit) {
        List<EmbeddedDocument> candidates = scopeId == null
                ? embeddedDocumentRepository.findByTenantIdAndEmbeddingIsNotNull(tenantId)
                : embeddedDocumentRepository.findByTenantIdAndScopeIdAndEmbeddingIsNotNull(tenantId, scopeId);
        if (candidates.isEmpty() || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        String raw = llmGatewayClient.embed(tenantId.toString(), embeddingModel, queryText);
        double[] query = EmbeddingParser.parse(objectMapper, raw);

        return candidates.stream()
                .sorted(Comparator.comparingDouble((EmbeddedDocument d) -> cosineSimilarity(query, d.getEmbedding())).reversed())
                .limit(limit)
                .toList();
    }

    private double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) {
            return -1;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
