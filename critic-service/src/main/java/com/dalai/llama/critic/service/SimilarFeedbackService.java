package com.dalai.llama.critic.service;

import com.dalai.llama.critic.domain.entity.CritiqueFeedback;
import com.dalai.llama.critic.dto.SimilarFeedbackView;
import com.dalai.llama.critic.repository.CritiqueFeedbackRepository;
import com.dalai.llama.critic.service.llmgateway.EmbeddingParser;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Retrieval side of the human-feedback embedding loop -- ranks a tenant's past {@link
 * CritiqueFeedback} rows by cosine similarity to a query string, in application code rather than
 * an indexed vector search (see {@code CritiqueFeedback}'s javadoc for why: no pgvector on this
 * cluster's Postgres). Fine at the feedback volumes a single tenant accumulates early on.
 * <p>
 * <b>Architecture note (revisited):</b> critic-service's own operational data (findings, plan
 * snapshots) stays here and stays decentralized -- every service in this system owns its own
 * domain data behind its own API, and that doesn't change. But the *chat-facing* memory layer is
 * project-scoped and cross-cutting by nature: a user chatting about a project needs to reason
 * across everything that happened to it (script, shots, critiques, generated media, human edits)
 * regardless of which service produced each piece, which argues for chat-service owning one
 * central, project-scoped embedding index rather than issuing a tool call per producing service
 * on every turn. Once chat-service exists, this class's role becomes a *push source* into that
 * central index (critic-service reports "feedback recorded" with its embedding) rather than the
 * primary retrieval path -- {@code GET /v1/critiques/similar-feedback} stays as critic-service's
 * own internal API either way. Not built now because chat-service is later in the build order.
 */
@Service
public class SimilarFeedbackService {

    private final CritiqueFeedbackRepository critiqueFeedbackRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String embeddingModel;

    public SimilarFeedbackService(
            CritiqueFeedbackRepository critiqueFeedbackRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${critic.llm-gateway.default-embedding-model}") String embeddingModel
    ) {
        this.critiqueFeedbackRepository = critiqueFeedbackRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
    }

    @Transactional(readOnly = true)
    public List<SimilarFeedbackView> findSimilarViews(UUID tenantId, String queryText, int limit) {
        return findSimilar(tenantId, queryText, limit).stream()
                .map(f -> new SimilarFeedbackView(f.getSessionId(), f.isApproved(), f.getEditLocations(), f.getReason(), f.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CritiqueFeedback> findSimilar(UUID tenantId, String queryText, int limit) {
        List<CritiqueFeedback> candidates = critiqueFeedbackRepository.findByTenantIdAndEmbeddingIsNotNull(tenantId);
        if (candidates.isEmpty() || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        String raw = llmGatewayClient.embed(tenantId.toString(), embeddingModel, queryText);
        double[] query = EmbeddingParser.parse(objectMapper, raw);

        return candidates.stream()
                .sorted(Comparator.comparingDouble((CritiqueFeedback f) -> cosineSimilarity(query, f.getEmbedding())).reversed())
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
