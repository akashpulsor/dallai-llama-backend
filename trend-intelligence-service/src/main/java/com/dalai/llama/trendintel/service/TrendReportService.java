package com.dalai.llama.trendintel.service;

import com.dalai.llama.trendintel.domain.entity.TrendPredictionItem;
import com.dalai.llama.trendintel.domain.entity.TrendReport;
import com.dalai.llama.trendintel.dto.GenerateTrendReportRequest;
import com.dalai.llama.trendintel.dto.TrendPredictionItemView;
import com.dalai.llama.trendintel.dto.TrendReportView;
import com.dalai.llama.trendintel.repository.TrendPredictionItemRepository;
import com.dalai.llama.trendintel.repository.TrendReportRepository;
import com.dalai.llama.trendintel.service.generation.JsonExtraction;
import com.dalai.llama.trendintel.service.generation.TrendPredictionItemContent;
import com.dalai.llama.trendintel.service.generation.TrendReportContent;
import com.dalai.llama.trendintel.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.trendintel.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.trendintel.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.trendintel.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "For now just ask Gemini" -- v1 scope on purpose: no critic harness, no external trend-data
 * source, no evidence-signal ingestion. One LLM call per report, the model's own trained
 * knowledge surfaced honestly. The {@code predictions[]} response shape (title, summary,
 * confidenceScore, rationale, evidenceType, suggestedTags) is copied directly from
 * creator-service's established TREND_PREDICT contract ({@code TrendPredictionService}) rather
 * than inventing a different one.
 */
@Service
public class TrendReportService {

    private static final String TASK_KEY = "TREND_INTELLIGENCE_REPORT";

    private final TrendReportRepository trendReportRepository;
    private final TrendPredictionItemRepository trendPredictionItemRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public TrendReportService(
            TrendReportRepository trendReportRepository,
            TrendPredictionItemRepository trendPredictionItemRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${trend-intelligence.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.trendReportRepository = trendReportRepository;
        this.trendPredictionItemRepository = trendPredictionItemRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public TrendReportView generate(UUID tenantId, GenerateTrendReportRequest request) {
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "trend-report-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of(
                                "topic", request.topic(),
                                "industry", orNotSpecified(request.industry()),
                                "targetAudience", orNotSpecified(request.targetAudience())
                        )));

        TrendReportContent content = parse(response);

        TrendReport report = trendReportRepository.save(TrendReport.builder()
                .tenantId(tenantId)
                .topic(request.topic())
                .industry(request.industry())
                .targetAudience(request.targetAudience())
                .createdAt(OffsetDateTime.now())
                .build());

        List<TrendPredictionItem> items = persistPredictions(report.getId(), content.predictions());
        return toView(report, items);
    }

    @Transactional(readOnly = true)
    public TrendReportView get(UUID tenantId, UUID reportId) {
        TrendReport report = trendReportRepository.findByIdAndTenantId(reportId, tenantId)
                .orElseThrow(() -> TrendIntelligenceException.notFound("No trend report " + reportId));
        return toView(report, trendPredictionItemRepository.findByReportIdOrderByItemOrderAsc(reportId));
    }

    @Transactional(readOnly = true)
    public List<TrendReportView> list(UUID tenantId) {
        return trendReportRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(report -> toView(report, trendPredictionItemRepository.findByReportIdOrderByItemOrderAsc(report.getId())))
                .toList();
    }

    private List<TrendPredictionItem> persistPredictions(UUID reportId, List<TrendPredictionItemContent> predictions) {
        if (predictions == null || predictions.isEmpty()) {
            return List.of();
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<TrendPredictionItem> saved = new ArrayList<>();
        int order = 0;
        for (TrendPredictionItemContent item : predictions) {
            if (item.title() == null || item.title().isBlank()) {
                continue;
            }
            saved.add(trendPredictionItemRepository.save(TrendPredictionItem.builder()
                    .reportId(reportId)
                    .itemOrder(order++)
                    .title(item.title())
                    .summary(item.summary())
                    .confidenceScore(item.confidenceScore())
                    .rationale(item.rationale())
                    .evidenceType(item.evidenceType() == null || item.evidenceType().isBlank() ? "HEURISTIC" : item.evidenceType())
                    .suggestedTags(item.suggestedTags() == null ? new String[0] : item.suggestedTags().toArray(new String[0]))
                    .createdAt(now)
                    .build()));
        }
        return saved;
    }

    private TrendReportContent parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw TrendIntelligenceException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), TrendReportContent.class);
        } catch (Exception ex) {
            throw TrendIntelligenceException.upstream("Could not parse " + TASK_KEY + " response as JSON: " + ex.getMessage());
        }
    }

    private String orNotSpecified(String value) {
        return value == null || value.isBlank() ? "(not specified)" : value;
    }

    private TrendReportView toView(TrendReport report, List<TrendPredictionItem> items) {
        List<TrendPredictionItemView> predictions = items.stream()
                .map(i -> new TrendPredictionItemView(i.getTitle(), i.getSummary(), i.getConfidenceScore(),
                        i.getRationale(), i.getEvidenceType(), i.getSuggestedTags() == null ? List.of() : List.of(i.getSuggestedTags())))
                .toList();
        return new TrendReportView(report.getId(), report.getTopic(), report.getIndustry(), report.getTargetAudience(),
                predictions, report.getCreatedAt());
    }
}
