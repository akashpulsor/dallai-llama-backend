package com.dalai.llama.creativeplanning.service;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.ProjectReferenceImageAnalysis;
import com.dalai.llama.creativeplanning.dto.ReferenceImageAnalysisView;
import com.dalai.llama.creativeplanning.repository.ProjectReferenceImageAnalysisRepository;
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

/** The "detailed vision analysis of an uploaded project reference image" flow -- same shape as
 * {@code ReferenceImageAnalysisService}, but for {@code ProjectReferenceImage} ("what the client
 * has in mind") rather than {@code ProductReferenceImage} (the actual product). {@code brand} is
 * nullable here since a standalone requirement may not have one yet -- {@link
 * ContextSummaryBuilder#brandSummary} already renders a null brand as "(no brand context set)". */
@Service
public class ProjectReferenceImageAnalysisService {

    private static final String TASK_KEY = "PROJECT_REFERENCE_IMAGE_ANALYSIS";

    private final ProjectReferenceImageAnalysisRepository projectReferenceImageAnalysisRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public ProjectReferenceImageAnalysisService(
            ProjectReferenceImageAnalysisRepository projectReferenceImageAnalysisRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${creative-planning.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.projectReferenceImageAnalysisRepository = projectReferenceImageAnalysisRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    /** REQUIRES_NEW -- see {@code ReferenceImageAnalysisService#analyze}'s javadoc for why: this
     * runs from inside {@code ReferenceMaterialAnalysisService}'s broader funding transaction, and
     * without its own transaction a failure here would mark that transaction rollback-only and
     * throw {@code UnexpectedRollbackException} on commit -- regardless of the caller's try/catch. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReferenceImageAnalysisView analyze(UUID tenantId, UUID referenceImageId, String imageDataUri, BrandContext brand) {
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "project-reference-image-analysis-" + referenceImageId,
                new LlmGatewayChatRequest(defaultModel,
                        List.of(new LlmGatewayMessage("user",
                                "Analyze this reference image the client shared to describe the creative direction they have in mind for their video.",
                                List.of(imageDataUri))),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of("brandContext", ContextSummaryBuilder.brandSummary(brand))));

        ReferenceImageAnalysisResult parsed = parse(response);

        ProjectReferenceImageAnalysis analysis = projectReferenceImageAnalysisRepository.save(ProjectReferenceImageAnalysis.builder()
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
        return projectReferenceImageAnalysisRepository.findByReferenceImageId(referenceImageId)
                .map(this::toView)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean hasAnalysis(UUID referenceImageId) {
        return projectReferenceImageAnalysisRepository.findByReferenceImageId(referenceImageId).isPresent();
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

    private ReferenceImageAnalysisView toView(ProjectReferenceImageAnalysis analysis) {
        return new ReferenceImageAnalysisView(analysis.getDescription(), analysis.getDominantColors(),
                analysis.getStyleNotes(), analysis.getSubjectMatter(), analysis.getSuggestedUseCase());
    }
}
