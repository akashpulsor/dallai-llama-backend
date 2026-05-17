package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.PromptTemplateType;
import com.dalai.llama.creator.domain.entity.CreatorCategory;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.domain.entity.CreatorPromptTemplate;
import com.dalai.llama.creator.domain.entity.CreatorTrend;
import com.dalai.llama.creator.domain.entity.CreatorTrendSignal;
import com.dalai.llama.creator.dto.request.TrendPredictionRequest;
import com.dalai.llama.creator.dto.response.TrendPredictionItemResponse;
import com.dalai.llama.creator.dto.response.TrendPredictionResponse;
import com.dalai.llama.creator.repository.CreatorCategoryRepository;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.dalai.llama.creator.repository.CreatorTrendRepository;
import com.dalai.llama.creator.repository.CreatorTrendSignalRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class TrendPredictionService {

    private static final String DEFAULT_PLATFORM = "instagram_reels";
    private static final String DEFAULT_COUNTRY = "IN";
    private static final int DEFAULT_HORIZON_HOURS = 24;

    private final CreatorCategoryRepository categoryRepository;
    private final CreatorTrendSignalRepository trendSignalRepository;
    private final CreatorTrendRepository trendRepository;
    private final CreatorPromptRunRepository promptRunRepository;
    private final PromptTemplateService promptTemplateService;
    private final CreatorAiService creatorAiService;
    private final GenerationJobService generationJobService;
    private final CreatorProperties properties;
    private final ObjectMapper objectMapper;

    public TrendPredictionService(
            CreatorCategoryRepository categoryRepository,
            CreatorTrendSignalRepository trendSignalRepository,
            CreatorTrendRepository trendRepository,
            CreatorPromptRunRepository promptRunRepository,
            PromptTemplateService promptTemplateService,
            CreatorAiService creatorAiService,
            GenerationJobService generationJobService,
            CreatorProperties properties,
            ObjectMapper objectMapper
    ) {
        this.categoryRepository = categoryRepository;
        this.trendSignalRepository = trendSignalRepository;
        this.trendRepository = trendRepository;
        this.promptRunRepository = promptRunRepository;
        this.promptTemplateService = promptTemplateService;
        this.creatorAiService = creatorAiService;
        this.generationJobService = generationJobService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TrendPredictionResponse predict(TrendPredictionRequest request, String tenantId, String userId) {
        String categoryCode = normalizeLower(request.category());
        CreatorCategory category = categoryRepository.findByCode(categoryCode)
                .filter(CreatorCategory::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or inactive category: " + request.category()));

        String platformCode = normalizeLowerOrDefault(request.platform(), DEFAULT_PLATFORM);
        String countryCode = normalizeUpperOrDefault(request.country(), DEFAULT_COUNTRY);
        int horizonHours = request.horizonHours() == null || request.horizonHours() <= 0
                ? DEFAULT_HORIZON_HOURS
                : request.horizonHours();
        String predictionMode = normalizeUpperOrDefault(request.predictionMode(), "HYBRID");

        List<CreatorTrendSignal> evidenceSignals = trendSignalRepository.findRecentSignals(
                categoryCode,
                platformCode,
                countryCode,
                PageRequest.of(0, 30)
        );

        List<Map<String, Object>> evidence = evidenceSignals.stream()
                .map(this::toEvidenceMap)
                .toList();
        List<CreatorTrend> sourceTrends;
        if (request.sourceTrendIds() == null || request.sourceTrendIds().isEmpty()) {
            sourceTrends = trendRepository.findTop20ByCategoryCodeAndPlatformCodeAndCountryCodeOrderByScoreDescLastSeenAtDesc(
                    categoryCode,
                    platformCode,
                    countryCode
            );
        } else {
            sourceTrends = trendRepository.findAllById(request.sourceTrendIds());
        }
        List<Map<String, Object>> sourceTrendEvidence = sourceTrends.stream()
                .filter(trend -> categoryCode.equals(trend.getCategoryCode()))
                .map(this::toSourceTrendMap)
                .toList();

        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("categoryCode", categoryCode);
        inputSnapshot.put("categoryLabel", category.getDisplayName());
        inputSnapshot.put("platformCode", platformCode);
        inputSnapshot.put("countryCode", countryCode);
        inputSnapshot.put("horizonHours", horizonHours);
        inputSnapshot.put("predictionMode", predictionMode);
        inputSnapshot.put("recentSignals", evidence);
        inputSnapshot.put("sourceTrends", sourceTrendEvidence);
        inputSnapshot.put("userSignals", nullToList(request.userSignals()));
        inputSnapshot.put("sourceTrendIds", nullToList(request.sourceTrendIds()));
        inputSnapshot.put("userContext", request.userContext());
        inputSnapshot.put("parameters", nullToMap(request.parameters()));
        inputSnapshot.put("predictionRules", List.of(
                "Use category evidence first.",
                "Label broad-history, leadership/action-cycle, or market-memory guesses as HEURISTIC.",
                "Do not claim source evidence when only heuristic reasoning is used."
        ));

        CreatorPromptTemplate template = promptTemplateService.getActiveTemplate(PromptTemplateType.TREND_PREDICT.name());
        Map<String, Object> renderVariables = new LinkedHashMap<>(inputSnapshot);
        renderVariables.put("recentSignalsJson", toJson(evidence));
        renderVariables.put("sourceTrendsJson", toJson(sourceTrendEvidence));
        renderVariables.put("userSignalsJson", toJson(nullToList(request.userSignals())));
        renderVariables.put("parametersJson", toJson(nullToMap(request.parameters())));
        String renderedPrompt = promptTemplateService.render(template, renderVariables);

        Map<String, Object> aiInput = new LinkedHashMap<>(inputSnapshot);
        aiInput.put("renderedPrompt", renderedPrompt);

        CreatorGenerationJob generationJob = generationJobService.startGenerationJob(
                PromptTemplateType.TREND_PREDICT.name(),
                tenantId,
                userId,
                null,
                aiInput
        );

        try {
            CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                    tenantId,
                    userId,
                    null,
                    generationJob.getId(),
                    null
            );
            CreatorAiService.MeteredAiResponse aiResponse =
                    creatorAiService.generateMetered(PromptTemplateType.TREND_PREDICT.name(), aiInput, usageContext);
            Map<String, Object> aiOutput = aiResponse.output();

            CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                    .tenantId(defaultString(tenantId, "unknown"))
                    .userId(defaultString(userId, "unknown"))
                    .jobId(generationJob.getId())
                    .promptTemplateId(template.getId())
                    .promptTemplateKey(template.getTemplateKey())
                    .promptTemplateVersion(template.getVersion())
                    .renderedPrompt(renderedPrompt)
                    .inputSnapshot(inputSnapshot)
                    .provider(creatorAiService.providerName())
                    .model(creatorAiService.modelName())
                    .outputPayload(aiOutput)
                    .tokenMetadata(aiResponse.tokenMetadata())
                    .costMetadata(aiResponse.costMetadata())
                    .status("COMPLETED")
                    .completedAt(OffsetDateTime.now())
                    .build());
            creatorAiService.publishBillingDebit(
                    PromptTemplateType.TREND_PREDICT.name(),
                    aiResponse,
                    usageContext.withPromptRunId(promptRun.getId())
            );

            List<TrendPredictionItemResponse> predictions =
                    persistPredictions(promptRun.getId(), categoryCode, platformCode, countryCode, evidence.size(), aiOutput);

            Map<String, Object> jobOutput = new LinkedHashMap<>();
            jobOutput.put("promptRunId", promptRun.getId().toString());
            jobOutput.put("predictionCount", predictions.size());
            jobOutput.put("aiOutput", aiOutput);
            generationJobService.completeGenerationJob(generationJob.getId(), jobOutput);

            return new TrendPredictionResponse(
                    promptRun.getId(),
                    template.getTemplateKey(),
                    template.getVersion(),
                    creatorAiService.providerName(),
                    creatorAiService.modelName(),
                    categoryCode,
                    platformCode,
                    countryCode,
                    horizonHours,
                    predictionMode,
                    evidence.size(),
                    predictions,
                    aiOutput
            );
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(generationJob.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private List<TrendPredictionItemResponse> persistPredictions(
            UUID promptRunId,
            String categoryCode,
            String platformCode,
            String countryCode,
            int evidenceCount,
            Map<String, Object> aiOutput
    ) {
        List<Map<String, Object>> outputPredictions = extractPredictions(aiOutput);
        List<TrendPredictionItemResponse> responses = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();

        for (Map<String, Object> prediction : outputPredictions) {
            String title = truncate(stringValue(prediction.get("title")), 240);
            if (title == null || title.isBlank()) {
                continue;
            }
            String summary = stringValue(prediction.get("summary"));
            BigDecimal confidence = decimalValue(prediction.get("confidenceScore"));
            String evidenceType = defaultString(stringValue(prediction.get("evidenceType")),
                    evidenceCount > 0 ? "EVIDENCE_BACKED" : "HEURISTIC");
            List<String> tags = stringList(prediction.get("suggestedTags"));

            CreatorTrend trend = trendRepository.save(CreatorTrend.builder()
                    .platformCode(platformCode)
                    .categoryCode(categoryCode)
                    .countryCode(countryCode)
                    .title(title)
                    .summary(summary)
                    .sourceName("ai_prediction")
                    .sourceUrl(null)
                    .score(confidence.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP))
                    .velocity(confidence.multiply(BigDecimal.valueOf(10)).setScale(2, RoundingMode.HALF_UP))
                    .tags(tags)
                    .sourcePayload(Map.of("evidenceSignalCount", evidenceCount))
                    .predictionPayload(Map.of(
                            "promptRunId", promptRunId.toString(),
                            "rationale", defaultString(stringValue(prediction.get("rationale")), ""),
                            "evidenceType", evidenceType,
                            "rawPrediction", prediction
                    ))
                    .status("ACTIVE")
                    .firstSeenAt(now)
                    .lastSeenAt(now)
                    .createdAt(now)
                    .build());

            responses.add(new TrendPredictionItemResponse(
                    trend.getId(),
                    trend.getTitle(),
                    trend.getSummary(),
                    confidence,
                    defaultString(stringValue(prediction.get("rationale")), ""),
                    evidenceType,
                    tags
            ));
        }

        return responses;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractPredictions(Map<String, Object> aiOutput) {
        Object predictions = aiOutput.get("predictions");
        if (predictions instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private Map<String, Object> toEvidenceMap(CreatorTrendSignal signal) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("title", signal.getTitle());
        evidence.put("summary", signal.getSummary());
        evidence.put("signalText", signal.getSignalText());
        evidence.put("sourcePlatform", signal.getSourcePlatformCode());
        evidence.put("connector", signal.getConnectorCode());
        evidence.put("sourceUrl", signal.getSourceUrl());
        evidence.put("rankScore", signal.getRankScore());
        evidence.put("observedAt", signal.getObservedAt());
        return evidence;
    }

    private Map<String, Object> toSourceTrendMap(CreatorTrend trend) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("trendId", trend.getId());
        evidence.put("title", trend.getTitle());
        evidence.put("summary", trend.getSummary());
        evidence.put("score", trend.getScore());
        evidence.put("velocity", trend.getVelocity());
        evidence.put("tags", trend.getTags());
        evidence.put("sourceName", trend.getSourceName());
        evidence.put("sourceUrl", trend.getSourceUrl());
        evidence.put("lastSeenAt", trend.getLastSeenAt());
        evidence.put("predictionPayload", trend.getPredictionPayload());
        return evidence;
    }

    private String normalizeLower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeLowerOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : normalizeLower(value);
    }

    private String normalizeUpperOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }

    private List<?> nullToList(List<?> value) {
        return value == null ? List.of() : value;
    }

    private Map<String, Object> nullToMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ex) {
            return BigDecimal.valueOf(0.5d).setScale(2, RoundingMode.HALF_UP);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .toList();
        }
        return List.of();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
