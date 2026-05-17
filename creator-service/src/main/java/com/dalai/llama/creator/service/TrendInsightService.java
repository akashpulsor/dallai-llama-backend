package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.PromptTemplateType;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.domain.entity.CreatorPromptTemplate;
import com.dalai.llama.creator.domain.entity.CreatorTrend;
import com.dalai.llama.creator.dto.response.TrendInsightResponse;
import com.dalai.llama.creator.dto.response.TrendPostingWindowResponse;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.dalai.llama.creator.repository.CreatorTrendRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class TrendInsightService {

    private final CreatorTrendRepository trendRepository;
    private final CreatorPromptRunRepository promptRunRepository;
    private final PromptTemplateService promptTemplateService;
    private final CreatorAiService creatorAiService;
    private final GenerationJobService generationJobService;
    private final CreatorProperties properties;
    private final ObjectMapper objectMapper;

    public TrendInsightService(
            CreatorTrendRepository trendRepository,
            CreatorPromptRunRepository promptRunRepository,
            PromptTemplateService promptTemplateService,
            CreatorAiService creatorAiService,
            GenerationJobService generationJobService,
            CreatorProperties properties,
            ObjectMapper objectMapper
    ) {
        this.trendRepository = trendRepository;
        this.promptRunRepository = promptRunRepository;
        this.promptTemplateService = promptTemplateService;
        this.creatorAiService = creatorAiService;
        this.generationJobService = generationJobService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TrendInsightResponse getInsight(UUID trendId, String tenantId, String userId, String country, String timezone) {
        CreatorTrend trend = trendRepository.findById(trendId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trend not found"));

        String countryCode = normalizeUpperOrDefault(country, trend.getCountryCode());
        String effectiveTimezone = timezone == null || timezone.isBlank() ? "Asia/Kolkata" : timezone.trim();
        Map<String, Object> trendSnapshot = toTrendSnapshot(trend);
        Map<String, Object> postingContext = buildPostingContext(trend, countryCode, effectiveTimezone);

        CreatorPromptTemplate template = promptTemplateService.getActiveTemplate(PromptTemplateType.TREND_INSIGHT.name());
        Map<String, Object> renderVariables = new LinkedHashMap<>();
        renderVariables.put("trendJson", toJson(trendSnapshot));
        renderVariables.put("postingContextJson", toJson(postingContext));
        renderVariables.put("countryCode", countryCode);
        renderVariables.put("timezone", effectiveTimezone);
        String renderedPrompt = promptTemplateService.render(template, renderVariables);

        Map<String, Object> aiInput = new LinkedHashMap<>();
        aiInput.put("trend", trendSnapshot);
        aiInput.put("postingContext", postingContext);
        aiInput.put("countryCode", countryCode);
        aiInput.put("timezone", effectiveTimezone);
        aiInput.put("renderedPrompt", renderedPrompt);

        CreatorGenerationJob generationJob = generationJobService.startGenerationJob(
                PromptTemplateType.TREND_INSIGHT.name(),
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
                    creatorAiService.generateMetered(PromptTemplateType.TREND_INSIGHT.name(), aiInput, usageContext);
            Map<String, Object> aiOutput = aiResponse.output();
            CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                    .tenantId(defaultString(tenantId, "unknown"))
                    .userId(defaultString(userId, "unknown"))
                    .jobId(generationJob.getId())
                    .promptTemplateId(template.getId())
                    .promptTemplateKey(template.getTemplateKey())
                    .promptTemplateVersion(template.getVersion())
                    .renderedPrompt(renderedPrompt)
                    .inputSnapshot(aiInput)
                    .provider(creatorAiService.providerName())
                    .model(creatorAiService.modelName())
                    .outputPayload(aiOutput)
                    .tokenMetadata(aiResponse.tokenMetadata())
                    .costMetadata(aiResponse.costMetadata())
                    .status("COMPLETED")
                    .completedAt(OffsetDateTime.now())
                    .build());
            creatorAiService.publishBillingDebit(
                    PromptTemplateType.TREND_INSIGHT.name(),
                    aiResponse,
                    usageContext.withPromptRunId(promptRun.getId())
            );

            Map<String, Object> jobOutput = new LinkedHashMap<>();
            jobOutput.put("promptRunId", promptRun.getId().toString());
            jobOutput.put("trendId", trend.getId().toString());
            jobOutput.put("aiOutput", aiOutput);
            generationJobService.completeGenerationJob(generationJob.getId(), jobOutput);

            return new TrendInsightResponse(
                    trend.getId(),
                    promptRun.getId(),
                    trend.getTitle(),
                    trend.getCategoryCode(),
                    trend.getPlatformCode(),
                    countryCode,
                    stringValue(aiOutput.get("summary"), trend.getSummary()),
                    stringList(aiOutput.get("whyItWorked")),
                    postingWindows(aiOutput.get("bestTimes"), effectiveTimezone),
                    stringList(aiOutput.get("creatorActions")),
                    decimalValue(aiOutput.get("confidenceScore")),
                    stringValue(aiOutput.get("evidenceType"), "AI_TIMING_MODEL"),
                    mapValue(aiOutput.get("postingStrategy")),
                    aiOutput,
                    OffsetDateTime.now()
            );
        } catch (RuntimeException ex) {
            generationJobService.failGenerationJob(generationJob.getId(), defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private Map<String, Object> toTrendSnapshot(CreatorTrend trend) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", trend.getId());
        snapshot.put("platformCode", trend.getPlatformCode());
        snapshot.put("categoryCode", trend.getCategoryCode());
        snapshot.put("countryCode", trend.getCountryCode());
        snapshot.put("title", trend.getTitle());
        snapshot.put("summary", trend.getSummary());
        snapshot.put("score", trend.getScore());
        snapshot.put("velocity", trend.getVelocity());
        snapshot.put("tags", trend.getTags());
        snapshot.put("sourceName", trend.getSourceName());
        snapshot.put("sourceUrl", trend.getSourceUrl());
        snapshot.put("sourcePayload", trend.getSourcePayload());
        snapshot.put("predictionPayload", trend.getPredictionPayload());
        snapshot.put("firstSeenAt", trend.getFirstSeenAt());
        snapshot.put("lastSeenAt", trend.getLastSeenAt());
        return snapshot;
    }

    private Map<String, Object> buildPostingContext(CreatorTrend trend, String countryCode, String timezone) {
        OffsetDateTime now = OffsetDateTime.now();
        long ageMinutes = minutesBetween(trend.getFirstSeenAt(), now);
        long minutesSinceLastSeen = minutesBetween(trend.getLastSeenAt(), now);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("countryCode", countryCode);
        context.put("timezone", timezone);
        context.put("trendAgeMinutes", ageMinutes);
        context.put("minutesSinceLastSeen", minutesSinceLastSeen);
        context.put("platformCode", trend.getPlatformCode());
        context.put("categoryCode", trend.getCategoryCode());
        context.put("score", trend.getScore());
        context.put("velocity", trend.getVelocity());
        context.put("rankingDelayInputs", Map.of(
                "platformSeedAudienceTest", "Short-form platforms usually test a post with a small audience before expanding distribution.",
                "velocityEffect", "Higher trend velocity should reduce recommended publishing lead time.",
                "freshnessEffect", "Older or stale trends need earlier posting before peak availability."
        ));
        context.put("candidateAudienceWindows", List.of(
                Map.of("label", "Morning commute / routine", "window", "6AM - 9AM", "availabilitySignal", "habit planning and commute browsing"),
                Map.of("label", "Lunch break discovery", "window", "12PM - 2PM", "availabilitySignal", "short discovery sessions and saves"),
                Map.of("label", "Evening prime scroll", "window", "6PM - 10PM", "availabilitySignal", "largest relaxed short-form attention block"),
                Map.of("label", "Late night niche", "window", "10PM - 12AM", "availabilitySignal", "focused fandom, study, entertainment, and creator deep-scroll behavior")
        ));
        context.put("aiDecisionInstruction", "Select final bestTimes by estimating platform ranking delay, audience availability in timezone, trend freshness, velocity, category intent, and likely save/share behavior.");
        return context;
    }

    @SuppressWarnings("unchecked")
    private List<TrendPostingWindowResponse> postingWindows(Object value, String fallbackTimezone) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .map(item -> new TrendPostingWindowResponse(
                            stringValue(item.get("label"), "Primary"),
                            stringValue(item.get("window"), "6PM - 10PM"),
                            stringValue(item.get("timezone"), fallbackTimezone),
                            stringValue(item.get("reason"), "High short-form attention window.")
                    ))
                    .toList();
        }
        return List.of(new TrendPostingWindowResponse(
                "AI pending",
                "Run insight",
                fallbackTimezone,
                "The AI provider did not return posting windows."
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private long minutesBetween(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(start, end).toMinutes());
    }

    private String normalizeUpperOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toUpperCase(Locale.ROOT);
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

    private String stringValue(Object value, String defaultValue) {
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
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

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }
}
