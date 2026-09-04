package com.dalai.llama.creativeplanning.service;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.ReferenceImageAnalysis;
import com.dalai.llama.creativeplanning.dto.ReferenceImageAnalysisView;
import com.dalai.llama.creativeplanning.repository.ReferenceImageAnalysisRepository;
import com.dalai.llama.creativeplanning.service.generation.ContextSummaryBuilder;
import com.dalai.llama.creativeplanning.service.generation.JsonExtraction;
import com.dalai.llama.creativeplanning.service.generation.ReferenceImageAnalysisResult;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The "detailed vision analysis of an uploaded reference image" flow -- one Gemini multimodal
 * call per image, typed result persisted (no jsonb blob). */
@Service
public class ReferenceImageAnalysisService {

    private static final String TASK_KEY = "REFERENCE_IMAGE_ANALYSIS";

    private final ReferenceImageAnalysisRepository referenceImageAnalysisRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public ReferenceImageAnalysisService(
            ReferenceImageAnalysisRepository referenceImageAnalysisRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${creative-planning.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.referenceImageAnalysisRepository = referenceImageAnalysisRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    /** REQUIRES_NEW -- {@link com.dalai.llama.creativeplanning.service.requirement.ReferenceMaterialAnalysisService}
     * calls this once per image from inside a broader funding transaction; without its own
     * transaction, an LLM/parse failure here marks that OUTER transaction rollback-only (Spring
     * does this at the proxy boundary regardless of whether the caller catches the exception),
     * which then throws {@code UnexpectedRollbackException} when the funding transaction tries to
     * commit -- surfacing as an unrelated 500 on the next request, and (via the Kafka listener
     * never acking) an infinite redelivery loop of the same payment event on every pod restart.
     * A dedicated transaction per image means one bad image can only ever roll back its own row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReferenceImageAnalysisView analyze(UUID tenantId, UUID referenceImageId, String imageDataUri, BrandContext brand) {
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "reference-image-analysis-" + referenceImageId,
                new LlmGatewayChatRequest(defaultModel,
                        List.of(new LlmGatewayMessage("user", "Analyze this reference image.", List.of(imageDataUri))),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of("brandContext", ContextSummaryBuilder.brandSummary(brand))));

        ReferenceImageAnalysisResult parsed = parse(response);

        ReferenceImageAnalysis analysis = referenceImageAnalysisRepository.save(ReferenceImageAnalysis.builder()
                .tenantId(tenantId)
                .referenceImageId(referenceImageId)
                .description(parsed.description())
                .dominantColors(parsed.dominantColors())
                .styleNotes(parsed.styleNotes())
                .subjectMatter(parsed.subjectMatter())
                .suggestedUseCase(parsed.suggestedUseCase())
                .createdAt(OffsetDateTime.now())
                .build());
        return toView(analysis);
    }

    @Transactional(readOnly = true)
    public ReferenceImageAnalysisView getIfPresent(UUID referenceImageId) {
        return referenceImageAnalysisRepository.findByReferenceImageId(referenceImageId)
                .map(this::toView)
                .orElse(null);
    }

    private ReferenceImageAnalysisResult parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw CreativePlanningException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ReferenceImageAnalysisResult.class);
        } catch (Exception ex) {
            throw CreativePlanningException.upstream("Could not parse " + TASK_KEY + " response as JSON: " + ex.getMessage());
        }
    }

    private ReferenceImageAnalysisView toView(ReferenceImageAnalysis analysis) {
        return new ReferenceImageAnalysisView(analysis.getDescription(), analysis.getDominantColors(),
                analysis.getStyleNotes(), analysis.getSubjectMatter(), analysis.getSuggestedUseCase());
    }
}
