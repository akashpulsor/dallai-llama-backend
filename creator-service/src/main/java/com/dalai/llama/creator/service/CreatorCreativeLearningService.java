package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorProject;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.repository.CreatorProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CreatorCreativeLearningService {

    private static final String MEMORY_KEY = "approvedCreativeLearningBatches";
    private static final int MAX_PROJECT_BATCHES = 12;
    private static final int MAX_RECENT_PROJECTS = 30;
    private static final int MAX_RULES_PER_BATCH = 12;
    private static final int MAX_RETRIEVED_RULES = 12;

    private final CreatorProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    public CreatorCreativeLearningService(
            CreatorProjectRepository projectRepository,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> recordApprovedReview(
            CreatorScript script,
            Object candidateValue,
            List<Integer> affectedShotNumbers,
            String tenantId,
            String userId
    ) {
        List<Map<String, Object>> rules = normalizeRules(candidateValue, productName(script));
        if (rules.isEmpty()) {
            return Map.of(
                    "status", "NO_GENERALIZABLE_RULES",
                    "approvedRuleCount", 0,
                    "message", "The current revision was applied, but no reusable future-ad rule was approved."
            );
        }
        if (script == null || script.getProjectId() == null) {
            return Map.of(
                    "status", "PROJECT_MEMORY_UNAVAILABLE",
                    "approvedRuleCount", 0,
                    "message", "The revision was applied without cross-project learning because no project memory was available."
            );
        }
        CreatorProject project = projectRepository
                .findByIdAndTenantIdAndUserId(script.getProjectId(), safe(tenantId), safe(userId))
                .orElse(null);
        if (project == null) {
            return Map.of(
                    "status", "PROJECT_MEMORY_UNAVAILABLE",
                    "approvedRuleCount", 0,
                    "message", "The revision was applied without cross-project learning because its project memory was unavailable."
            );
        }

        Map<String, Object> scriptPayload = script.getScriptPayload() == null
                ? Map.of()
                : script.getScriptPayload();
        String categoryCode = firstText(script.getCategoryCode(), scriptPayload.get("categoryCode"), scriptPayload.get("category"));
        String adFormat = firstText(
                mapValue(scriptPayload.get("creativeDirection")).get("adFormat"),
                mapValue(scriptPayload.get("productIntelligenceBrief")).get("adFormat"),
                mapValue(scriptPayload.get("creatorContext")).get("adFormat")
        );
        String productCategory = firstText(
                mapValue(scriptPayload.get("productUnderstanding")).get("productCategory"),
                mapValue(scriptPayload.get("productIntelligenceBrief")).get("productCategory"),
                mapValue(scriptPayload.get("creativeDirection")).get("productCategory")
        );
        String fingerprint = UUID.nameUUIDFromBytes(toJson(rules).getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> memory = new LinkedHashMap<>(project.getMemorySnapshot() == null
                ? Map.of()
                : project.getMemorySnapshot());
        List<Map<String, Object>> batches = new ArrayList<>(mapList(memory.get(MEMORY_KEY)));
        Map<String, Object> existing = batches.stream()
                .filter(batch -> Boolean.TRUE.equals(batch.get("active")))
                .filter(batch -> fingerprint.equals(text(batch.get("fingerprint"))))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return summary(existing, "ALREADY_APPROVED");
        }

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batchId", UUID.randomUUID().toString());
        batch.put("fingerprint", fingerprint);
        batch.put("status", "APPROVED");
        batch.put("active", true);
        batch.put("sourceProjectId", project.getId().toString());
        batch.put("sourceScriptId", script.getId().toString());
        batch.put("categoryCode", categoryCode);
        batch.put("adFormat", adFormat);
        batch.put("productCategory", productCategory);
        batch.put("affectedShotNumbers", affectedShotNumbers == null ? List.of() : affectedShotNumbers.stream().distinct().sorted().toList());
        batch.put("rules", rules);
        batch.put("approvedAt", OffsetDateTime.now().toString());
        batch.put("approvedBy", safe(userId));
        batch.put("scope", "TENANT_USER_PRODUCT_ADS");
        batch.put("referencePolicy", "Store generalized principles only; never store or reuse reference-image pixels, URLs, logos, packaging copy, or product identity.");
        batches.add(batch);
        while (batches.size() > MAX_PROJECT_BATCHES) batches.remove(0);
        memory.put(MEMORY_KEY, batches);
        memory.put("creativeLearningUpdatedAt", OffsetDateTime.now().toString());
        project.setMemorySnapshot(memory);
        projectRepository.save(project);
        return summary(batch, "APPROVED");
    }

    @Transactional
    public void deactivateApprovedBatch(
            UUID projectId,
            String batchId,
            String tenantId,
            String userId
    ) {
        if (projectId == null || batchId == null || batchId.isBlank()) return;
        CreatorProject project = projectRepository
                .findByIdAndTenantIdAndUserId(projectId, safe(tenantId), safe(userId))
                .orElse(null);
        if (project == null) return;
        Map<String, Object> memory = new LinkedHashMap<>(project.getMemorySnapshot() == null
                ? Map.of()
                : project.getMemorySnapshot());
        List<Map<String, Object>> batches = mapList(memory.get(MEMORY_KEY)).stream()
                .map(source -> {
                    Map<String, Object> batch = new LinkedHashMap<>(source);
                    if (batchId.equals(text(batch.get("batchId"))) && Boolean.TRUE.equals(batch.get("active"))) {
                        batch.put("active", false);
                        batch.put("status", "REVERTED");
                        batch.put("revertedAt", OffsetDateTime.now().toString());
                    }
                    return batch;
                })
                .toList();
        memory.put(MEMORY_KEY, batches);
        memory.put("creativeLearningUpdatedAt", OffsetDateTime.now().toString());
        project.setMemorySnapshot(memory);
        projectRepository.save(project);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> approvedGuidance(
            String tenantId,
            String userId,
            String categoryCode,
            String adFormat,
            String productCategory
    ) {
        List<RankedBatch> batches = new ArrayList<>();
        for (CreatorProject project : projectRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(
                safe(tenantId),
                safe(userId),
                PageRequest.of(0, MAX_RECENT_PROJECTS)
        )) {
            for (Map<String, Object> batch : mapList(project.getMemorySnapshot() == null
                    ? null
                    : project.getMemorySnapshot().get(MEMORY_KEY))) {
                if (!Boolean.TRUE.equals(batch.get("active"))) continue;
                batches.add(new RankedBatch(
                        batch,
                        relevance(batch, categoryCode, adFormat, productCategory),
                        text(batch.get("approvedAt"))
                ));
            }
        }
        batches.sort(Comparator.comparingInt(RankedBatch::score).reversed()
                .thenComparing(RankedBatch::approvedAt, Comparator.reverseOrder()));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RankedBatch ranked : batches) {
            Map<String, Object> batch = ranked.batch();
            for (Map<String, Object> rule : mapList(batch.get("rules"))) {
                String principle = text(rule.get("principle"));
                String key = principle.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                if (principle.isBlank() || !seen.add(key)) continue;
                Map<String, Object> guidance = new LinkedHashMap<>(rule);
                guidance.put("categoryCode", text(batch.get("categoryCode")));
                guidance.put("adFormat", text(batch.get("adFormat")));
                guidance.put("productCategory", text(batch.get("productCategory")));
                guidance.put("approvedAt", text(batch.get("approvedAt")));
                guidance.put("relevanceScore", ranked.score());
                result.add(guidance);
                if (result.size() >= MAX_RETRIEVED_RULES) return result;
            }
        }
        return result;
    }

    public String appendApprovedGuidance(String prompt, List<Map<String, Object>> guidance) {
        String base = prompt == null ? "" : prompt;
        if (guidance == null || guidance.isEmpty()) return base;
        return base + "\n\nAPPROVED CREATIVE LEARNINGS FROM EARLIER CLIENT-ACCEPTED ADS:\n"
                + toJson(guidance)
                + "\nApply only rules whose category/ad-format context matches this ad. Treat these as creative principles, not factual product evidence. "
                + "Never reuse a prior product name, logo, packaging copy, claim, or reference-image composition. Current approved product facts and supplied references always win.";
    }

    private List<Map<String, Object>> normalizeRules(Object value, String productName) {
        Object source = value;
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = mapValue(raw);
            source = firstNonNull(map.get("rules"), map.get("creativeLearnings"), map.get("learningCandidates"));
        }
        List<?> items = source instanceof List<?> list ? list : source == null ? List.of() : List.of(source);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (result.size() >= MAX_RULES_PER_BATCH) break;
            Map<String, Object> map = mapValue(item);
            String principle = clean(firstText(
                    map.get("principle"),
                    map.get("rule"),
                    map.get("learning"),
                    item instanceof String ? item : null
            ), productName, 700);
            if (principle.isBlank()) continue;
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("principle", principle);
            rule.put("appliesWhen", clean(firstText(map.get("appliesWhen"), map.get("scope")), productName, 360));
            rule.put("avoid", clean(firstText(map.get("avoid"), map.get("negativeRule")), productName, 360));
            rule.put("rationale", clean(map.get("rationale"), productName, 500));
            rule.put("tags", stringList(map.get("tags")).stream()
                    .map(tag -> clean(tag, productName, 60))
                    .filter(tag -> !tag.isBlank())
                    .distinct()
                    .limit(8)
                    .toList());
            rule.put("priority", normalizePriority(map.get("priority")));
            result.add(rule);
        }
        return result;
    }

    private Map<String, Object> summary(Map<String, Object> batch, String status) {
        List<Map<String, Object>> rules = mapList(batch.get("rules"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", status);
        summary.put("batchId", text(batch.get("batchId")));
        summary.put("approvedRuleCount", rules.size());
        summary.put("rules", rules);
        summary.put("approvedAt", text(batch.get("approvedAt")));
        summary.put("scope", text(batch.get("scope")));
        summary.put("message", rules.size() + " approved creative principle" + (rules.size() == 1 ? "" : "s")
                + " will guide matching future product ads.");
        return summary;
    }

    private int relevance(Map<String, Object> batch, String categoryCode, String adFormat, String productCategory) {
        int score = 1;
        if (matches(batch.get("categoryCode"), categoryCode)) score += 4;
        if (matches(batch.get("adFormat"), adFormat)) score += 5;
        if (matches(batch.get("productCategory"), productCategory)) score += 3;
        return score;
    }

    private boolean matches(Object stored, Object requested) {
        String left = text(stored).trim();
        String right = text(requested).trim();
        return !left.isBlank() && !right.isBlank() && left.equalsIgnoreCase(right);
    }

    private String productName(CreatorScript script) {
        if (script == null || script.getScriptPayload() == null) return "";
        Map<String, Object> payload = script.getScriptPayload();
        return firstText(
                mapValue(payload.get("productUnderstanding")).get("productName"),
                mapValue(payload.get("productIntelligenceBrief")).get("productName"),
                payload.get("productName")
        );
    }

    private String clean(Object value, String productName, int maxLength) {
        String result = text(value)
                .replaceAll("(?i)https?://\\S+", "")
                .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}]", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (productName != null && !productName.isBlank()) {
            result = result.replaceAll("(?i)" + Pattern.quote(productName.trim()), "the approved product");
        }
        return result.length() <= maxLength ? result : result.substring(0, maxLength).trim();
    }

    private String normalizePriority(Object value) {
        return switch (text(value).toUpperCase(Locale.ROOT)) {
            case "HIGH", "CRITICAL" -> "HIGH";
            case "LOW" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> mapValue(item))
                .toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::text).filter(item -> !item.isBlank()).toList();
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) if (value != null) return value;
        return null;
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = text(value).trim();
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private record RankedBatch(Map<String, Object> batch, int score, String approvedAt) {
    }
}
