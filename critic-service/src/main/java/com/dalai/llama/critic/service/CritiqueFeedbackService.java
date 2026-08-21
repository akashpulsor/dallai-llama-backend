package com.dalai.llama.critic.service;

import com.dalai.llama.critic.domain.entity.CritiqueFeedback;
import com.dalai.llama.critic.domain.entity.CritiqueSession;
import com.dalai.llama.critic.dto.RecordFeedbackRequest;
import com.dalai.llama.critic.repository.CritiqueFeedbackRepository;
import com.dalai.llama.critic.repository.CritiqueSessionRepository;
import com.dalai.llama.critic.service.llmgateway.EmbeddingParser;
import com.dalai.llama.critic.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Raw capture of a human's post-generation verdict, tenant-scoped, embedded at write time so
 * {@link SimilarFeedbackService} can retrieve it later -- see {@link
 * com.dalai.llama.critic.domain.entity.CritiqueFeedback}'s own javadoc for the storage tradeoff
 * (plain array, not pgvector). */
@Service
public class CritiqueFeedbackService {

    private final CritiqueSessionRepository critiqueSessionRepository;
    private final CritiqueFeedbackRepository critiqueFeedbackRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String embeddingModel;

    public CritiqueFeedbackService(
            CritiqueSessionRepository critiqueSessionRepository,
            CritiqueFeedbackRepository critiqueFeedbackRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${critic.llm-gateway.default-embedding-model}") String embeddingModel
    ) {
        this.critiqueSessionRepository = critiqueSessionRepository;
        this.critiqueFeedbackRepository = critiqueFeedbackRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
    }

    @Transactional
    public void record(UUID tenantId, UUID sessionId, RecordFeedbackRequest request) {
        CritiqueSession session = critiqueSessionRepository.findByIdAndTenantId(sessionId, tenantId)
                .orElseThrow(() -> CriticException.notFound("No critique session " + sessionId));

        String feedbackText = feedbackText(request);
        double[] embedding = null;
        if (!feedbackText.isBlank()) {
            String raw = llmGatewayClient.embed(tenantId.toString(), embeddingModel, feedbackText);
            embedding = EmbeddingParser.parse(objectMapper, raw);
        }

        critiqueFeedbackRepository.save(CritiqueFeedback.builder()
                .sessionId(session.getId())
                .tenantId(tenantId)
                .approved(request.approved())
                .editLocations(request.editLocations())
                .reason(request.reason())
                .embedding(embedding)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    private String feedbackText(RecordFeedbackRequest request) {
        StringBuilder text = new StringBuilder(request.approved() ? "approved" : "disapproved");
        if (request.editLocations() != null && !request.editLocations().isBlank()) {
            text.append(" edits: ").append(request.editLocations());
        }
        if (request.reason() != null && !request.reason().isBlank()) {
            text.append(" reason: ").append(request.reason());
        }
        return text.toString();
    }
}
