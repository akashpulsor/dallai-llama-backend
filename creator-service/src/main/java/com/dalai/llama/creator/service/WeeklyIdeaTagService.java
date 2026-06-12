package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class WeeklyIdeaTagService {

    public static final String PROMPT_KEY = "WEEKLY_IDEA_TAGS";
    private static final Logger log = LoggerFactory.getLogger(WeeklyIdeaTagService.class);
    private static final List<CategorySpec> CATEGORY_SPECS = List.of(
            new CategorySpec("history", "History", 6),
            new CategorySpec("politics", "Politics", 5),
            new CategorySpec("sports", "Sports", 6),
            new CategorySpec("entertainment", "Entertainment", 6),
            new CategorySpec("bollywood", "Bollywood", 7)
    );

    private final CreatorPromptRunRepository promptRunRepository;
    private final CreatorAiService creatorAiService;
    private final ObjectMapper objectMapper;

    public WeeklyIdeaTagService(
            CreatorPromptRunRepository promptRunRepository,
            CreatorAiService creatorAiService,
            ObjectMapper objectMapper
    ) {
        this.promptRunRepository = promptRunRepository;
        this.creatorAiService = creatorAiService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> latest() {
        return promptRunRepository.findTop1ByPromptTemplateKeyAndStatusOrderByCreatedAtDesc(PROMPT_KEY, "COMPLETED")
                .map(this::toResponse)
                .orElseGet(this::emptyResponse);
    }

    @Transactional
    public Map<String, Object> refreshIfStale(String tenantId, String userId, String trigger, Duration freshness) {
        Duration safeFreshness = freshness == null || freshness.isZero() || freshness.isNegative()
                ? Duration.ofDays(7)
                : freshness;
        OffsetDateTime cutoff = OffsetDateTime.now().minus(safeFreshness);
        return promptRunRepository
                .findTop1ByPromptTemplateKeyAndStatusAndCompletedAtAfterOrderByCompletedAtDesc(PROMPT_KEY, "COMPLETED", cutoff)
                .map(promptRun -> {
                    Map<String, Object> response = toResponse(promptRun);
                    response.put("refreshSkipped", true);
                    response.put("skipReason", "fresh_weekly_idea_tags");
                    response.put("freshnessMs", safeFreshness.toMillis());
                    log.info(
                            "Weekly creator idea tags refresh skipped because fresh completed prompt run exists promptRunId={} completedAt={} freshnessMs={}",
                            promptRun.getId(),
                            promptRun.getCompletedAt(),
                            safeFreshness.toMillis()
                    );
                    return response;
                })
                .orElseGet(() -> refresh(tenantId, userId, trigger));
    }

    @Transactional
    public Map<String, Object> refresh(String tenantId, String userId, String trigger) {
        String safeTenantId = defaultString(tenantId, "system");
        String safeUserId = defaultString(userId, "system");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", weeklyIdeaPrompt());
        input.put("useGoogleSearch", true);
        input.put("horizonDays", 7);
        input.put("ideaCount", targetIdeaCount());
        input.put("countryFocus", "IN");
        input.put("categories", CATEGORY_SPECS.stream().map(spec -> Map.of(
                "category", spec.code(),
                "label", spec.label(),
                "count", spec.count()
        )).toList());
        input.put("trigger", defaultString(trigger, "MANUAL"));
        input.put("generatedFor", "creator_new_idea_tag_cloud");

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                safeTenantId,
                safeUserId,
                null,
                null,
                null
        );
        CreatorAiService.MeteredAiResponse aiResponse =
                creatorAiService.generateMetered(PROMPT_KEY, input, usageContext);
        Map<String, Object> normalizedOutput = normalizeOutput(aiResponse.output());

        CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                .tenantId(safeTenantId)
                .userId(safeUserId)
                .promptTemplateKey(PROMPT_KEY)
                .promptTemplateVersion(1)
                .renderedPrompt(String.valueOf(input.get("renderedPrompt")))
                .inputSnapshot(input)
                .provider(creatorAiService.providerName())
                .model(creatorAiService.modelName())
                .outputPayload(normalizedOutput)
                .tokenMetadata(aiResponse.tokenMetadata())
                .costMetadata(aiResponse.costMetadata())
                .status("COMPLETED")
                .completedAt(OffsetDateTime.now())
                .build());
        creatorAiService.publishBillingDebit(PROMPT_KEY, aiResponse, usageContext.withPromptRunId(promptRun.getId()));

        log.info(
                "Weekly creator idea tags refreshed promptRunId={} provider={} model={} trigger={} totalTags={}",
                promptRun.getId(),
                creatorAiService.providerName(),
                creatorAiService.modelName(),
                trigger,
                tagCloud(normalizedOutput).size()
        );
        return toResponse(promptRun);
    }

    private Map<String, Object> toResponse(CreatorPromptRun promptRun) {
        Map<String, Object> output = normalizeOutput(promptRun.getOutputPayload());
        Map<String, Object> response = new LinkedHashMap<>(output);
        response.put("promptRunId", promptRun.getId().toString());
        response.put("provider", promptRun.getProvider());
        response.put("model", promptRun.getModel());
        response.put("generatedAt", promptRun.getCompletedAt() == null ? promptRun.getCreatedAt() : promptRun.getCompletedAt());
        response.put("createdAt", promptRun.getCreatedAt());
        response.put("status", promptRun.getStatus());
        response.put("tokenMetadata", promptRun.getTokenMetadata());
        response.put("costMetadata", promptRun.getCostMetadata());
        return response;
    }

    private int targetIdeaCount() {
        return CATEGORY_SPECS.stream().mapToInt(CategorySpec::count).sum();
    }

    private Map<String, Object> emptyResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "EMPTY");
        response.put("horizonDays", 7);
        response.put("ideaCount", 0);
        response.put("categories", CATEGORY_SPECS.stream().map(spec -> Map.of(
                "category", spec.code(),
                "label", spec.label(),
                "ideas", List.of()
        )).toList());
        response.put("tags", List.of());
        response.put("message", "Weekly idea tags have not been generated yet.");
        return response;
    }

    private Map<String, Object> normalizeOutput(Map<String, Object> rawOutput) {
        Map<String, Object> output = new LinkedHashMap<>(rawOutput == null ? Map.of() : rawOutput);
        List<Map<String, Object>> categories = normalizeCategories(output.get("categories"));
        if (categories.isEmpty()) {
            categories = categoriesFromFlatIdeas(output.get("ideas"));
        }
        if (categories.isEmpty()) {
            categories = emptyCategories();
        }
        List<Map<String, Object>> tags = tagCloud(Map.of("categories", categories));
        output.put("categories", categories);
        output.put("tags", tags);
        output.put("ideaCount", tags.size());
        output.put("horizonDays", 7);
        output.put("categoryOrder", CATEGORY_SPECS.stream().map(CategorySpec::code).toList());
        output.putIfAbsent("status", "completed");
        output.putIfAbsent("generatedFor", "creator_new_idea_tag_cloud");
        return output;
    }

    private List<Map<String, Object>> normalizeCategories(Object value) {
        List<Map<String, Object>> source = mapList(value);
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (CategorySpec spec : CATEGORY_SPECS) {
            Map<String, Object> matched = source.stream()
                    .filter(item -> spec.code().equals(normalizeCode(firstString(item.get("category"), item.get("code"), item.get("id"), item.get("label")))))
                    .findFirst()
                    .orElse(Map.of());
            List<Map<String, Object>> ideas = normalizeIdeas(matched.get("ideas"), spec);
            normalized.add(categoryMap(spec, ideas));
        }
        return normalized;
    }

    private List<Map<String, Object>> categoriesFromFlatIdeas(Object value) {
        List<Map<String, Object>> source = mapList(value);
        if (source.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> categories = new ArrayList<>();
        for (CategorySpec spec : CATEGORY_SPECS) {
            List<Map<String, Object>> ideas = source.stream()
                    .filter(item -> spec.code().equals(normalizeCode(firstString(item.get("category"), item.get("code"), item.get("categoryCode")))))
                    .map(item -> normalizeIdea(item, spec, 0))
                    .filter(item -> !stringValue(item.get("title")).isBlank())
                    .limit(spec.count())
                    .toList();
            categories.add(categoryMap(spec, ideas));
        }
        return categories;
    }

    private List<Map<String, Object>> normalizeIdeas(Object value, CategorySpec spec) {
        List<Map<String, Object>> ideas = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> item : mapList(value)) {
            Map<String, Object> normalized = normalizeIdea(item, spec, index);
            if (!stringValue(normalized.get("title")).isBlank()) {
                ideas.add(normalized);
                index++;
            }
            if (ideas.size() >= spec.count()) {
                break;
            }
        }
        return ideas;
    }

    private Map<String, Object> normalizeIdea(Map<String, Object> item, CategorySpec spec, int index) {
        String title = truncate(firstString(item.get("title"), item.get("tag"), item.get("topic"), item.get("idea")), 120);
        String prompt = truncate(firstString(item.get("prompt"), item.get("creatorPrompt"), item.get("brief"), title), 260);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", spec.code() + "-" + slug(title.isBlank() ? "idea-" + index : title));
        normalized.put("category", spec.code());
        normalized.put("categoryLabel", spec.label());
        normalized.put("title", title);
        normalized.put("prompt", prompt);
        normalized.put("why", truncate(firstString(item.get("why"), item.get("reason"), item.get("rationale"), item.get("summary")), 260));
        normalized.put("expectedWindow", truncate(firstString(item.get("expectedWindow"), item.get("timing"), item.get("trendWindow")), 80));
        normalized.put("confidence", firstString(item.get("confidence"), item.get("confidenceScore"), item.get("score")));
        normalized.put("sourceHint", truncate(firstString(item.get("sourceHint"), item.get("source"), item.get("evidence")), 180));
        return normalized;
    }

    private List<Map<String, Object>> tagCloud(Map<String, Object> output) {
        List<Map<String, Object>> tags = new ArrayList<>();
        for (Map<String, Object> category : mapList(output.get("categories"))) {
            for (Map<String, Object> idea : mapList(category.get("ideas"))) {
                if (stringValue(idea.get("title")).isBlank()) {
                    continue;
                }
                Map<String, Object> tag = new LinkedHashMap<>(idea);
                tag.put("label", idea.get("title"));
                tags.add(tag);
            }
        }
        return tags.stream().limit(targetIdeaCount()).toList();
    }

    private List<Map<String, Object>> emptyCategories() {
        return CATEGORY_SPECS.stream()
                .map(spec -> categoryMap(spec, List.of()))
                .toList();
    }

    private Map<String, Object> categoryMap(CategorySpec spec, List<Map<String, Object>> ideas) {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("category", spec.code());
        category.put("label", spec.label());
        category.put("targetCount", spec.count());
        category.put("ideas", ideas == null ? List.of() : ideas);
        return category;
    }

    private String weeklyIdeaPrompt() {
        String categoryBreakdown = CATEGORY_SPECS.stream()
                .map(spec -> "- " + spec.code() + ": " + spec.count())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return """
                You are a web-aware trend scout for Dalaillama Creator Studio.

                Use Google Search grounding / current web evidence to suggest creator video idea tags that could trend in India in the next 7 days.

                Return exactly %d short idea tags divided as:
                %s

                Rules:
                - Prefer topics that can become YouTube Shorts, Instagram Reels, or creator talking-head videos.
                - Avoid unsafe, defamatory, explicit, or hate content.
                - Do not claim certainty. These are forecast ideas, not facts.
                - Keep each title under 8 words.
                - The prompt field must be a useful creator brief that can be pasted into a topic box.
                - Include why, expectedWindow, confidence, and sourceHint.
                - Return only valid JSON.

                JSON shape:
                {
                  "summary": "short summary",
                  "categories": [
                    {
                      "category": "history",
                      "label": "History",
                      "ideas": [
                        {
                          "title": "short tag",
                          "prompt": "creator brief to paste into topic box",
                          "why": "why this could trend",
                          "expectedWindow": "next 7 days timing hint",
                          "confidence": "low|medium|high",
                          "sourceHint": "web signal type, not a long citation"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(targetIdeaCount(), categoryBreakdown);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private String firstString(Object... values) {
        for (Object value : values) {
            String text = stringValue(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String normalizeCode(String value) {
        String code = stringValue(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if ("sport".equals(code)) return "sports";
        if ("bolly_wood".equals(code) || "bolly".equals(code)) return "bollywood";
        if ("political".equals(code) || "political_news".equals(code) || "current_affairs".equals(code) || "election".equals(code) || "elections".equals(code)) return "politics";
        return code;
    }

    private String slug(String value) {
        String slug = normalizeCode(value);
        return slug.isBlank() ? UUID.randomUUID().toString() : slug;
    }

    private String truncate(String value, int maxLength) {
        String text = stringValue(value).trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength)).trim();
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private record CategorySpec(String code, String label, int count) {
    }
}
