package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ShortHookGenerationService {

    public HookGenerationResult generate(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> compressionPlans,
            List<Map<String, Object>> plannerCandidates,
            Map<String, Object> storyCritic,
            Map<String, Object> interestingnessCritic
    ) {
        List<Map<String, Object>> plans = copyList(compressionPlans);
        List<Map<String, Object>> candidates = copyList(plannerCandidates);
        int requested = Math.max(1, Math.min(video == null || video.getRequestedShorts() == null ? 20 : video.getRequestedShorts(), 50));

        if (candidates.isEmpty()) {
            for (Map<String, Object> plan : plans) {
                candidates.add(candidateFromPlan(video, plan));
            }
        }
        while (candidates.size() < requested && !plans.isEmpty()) {
            candidates.add(candidateFromPlan(video, plans.get(candidates.size() % plans.size())));
        }

        List<Map<String, Object>> generated = new ArrayList<>();
        List<Map<String, Object>> hooks = new ArrayList<>();
        List<Map<String, Object>> issues = new ArrayList<>();
        int limit = Math.min(candidates.size(), requested);
        for (int index = 0; index < limit; index++) {
            Map<String, Object> candidate = new LinkedHashMap<>(candidates.get(index));
            Map<String, Object> plan = plans.isEmpty() ? new LinkedHashMap<>() : plans.get(index % plans.size());
            HookDecision hook = hookDecision(video, videoDna, graph, candidate, plan, storyCritic, interestingnessCritic, index + 1);
            applyHook(candidate, hook);
            generated.add(candidate);
            hooks.add(hook.toMap());
            issues.addAll(hook.issues());
        }

        String status = generated.isEmpty() ? "WARN" : issues.stream().anyMatch(issue -> "high".equalsIgnoreCase(stringValue(issue.get("severity"), ""))) ? "WARN" : "COMPLETED";
        Map<String, Object> traceMetadata = new LinkedHashMap<>();
        traceMetadata.put("source", "deterministic_hook_generation_worker");
        traceMetadata.put("candidateCount", generated.size());
        traceMetadata.put("hookCount", hooks.size());
        traceMetadata.put("issueCount", issues.size());
        traceMetadata.put("issues", issues);

        List<Map<String, Object>> trace = List.of(traceRow(
                "HOOK_GENERATION",
                status,
                generated.isEmpty()
                        ? "No hooks were generated because no candidate or compression plan was available."
                        : "Generated source-grounded hooks from compression EDLs, story constraints, and interestingness critic output.",
                generated.isEmpty() ? 0.25 : issues.isEmpty() ? 0.9 : 0.76,
                traceMetadata
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "deterministic_hook_generation_worker");
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("candidateCount", generated.size());
        metadata.put("hookCount", hooks.size());
        metadata.put("issues", issues);
        metadata.put("hooks", hooks);
        metadata.put("videoType", stringValue(videoDna == null ? null : videoDna.get("primaryType"), "unknown"));
        metadata.put("graphSource", stringValue(graph == null ? null : graph.get("analysisSource"), "unknown"));

        return new HookGenerationResult(generated, hooks, trace, metadata);
    }

    private HookDecision hookDecision(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            Map<String, Object> graph,
            Map<String, Object> candidate,
            Map<String, Object> plan,
            Map<String, Object> storyCritic,
            Map<String, Object> interestingnessCritic,
            int rank
    ) {
        Map<String, Object> edl = mapValue(firstNonEmpty(plan.get("editDecisionList"), candidate.get("editDecisionList")));
        List<Map<String, Object>> segments = listOfMaps(edl.get("segments"));
        String chainType = stringValue(firstNonEmpty(plan.get("chainType"), edlChainType(edl)), "INTERESTINGNESS");
        String strategy = stringValue(edl.get("strategy"), stringValue(plan.get("hookType"), ""));
        String firstText = firstSegmentText(segments);
        String payoffText = lastSegmentText(segments);
        String candidateTitle = stringValue(candidate.get("title"), "");
        String planTitle = stringValue(plan.get("title"), "");
        String topic = topic(video, firstText, payoffText, candidateTitle, planTitle);
        int score = Math.max(0, Math.min(100, intValue(firstNonEmpty(plan.get("score"), candidate.get("score")), 80)));

        List<Map<String, Object>> issues = new ArrayList<>();
        List<String> sourceNodeIds = sourceNodeIds(segments);
        List<String> riskFlags = riskFlags(firstText, payoffText, sourceNodeIds, storyCritic, interestingnessCritic);
        if (sourceNodeIds.isEmpty()) {
            issues.add(issue("high", "HOOK_NO_SOURCE_NODE", "Hook has no source transcript node."));
        }
        if (firstText.length() < 8) {
            issues.add(issue("medium", "HOOK_WEAK_OPENING_TEXT", "Opening source text is too short."));
        }
        if (!riskFlags.isEmpty()) {
            issues.add(issue("medium", "HOOK_RISK_FLAGS", "Hook has risk flags that downstream critic should review."));
        }

        String hookType = hookTypeFor(chainType, strategy, firstText, payoffText, score, rank);
        String openingLine = openingLineFor(hookType, firstText, payoffText, topic);
        String onScreenHook = onScreenHook(openingLine, topic);
        String title = titleFor(video, candidateTitle, planTitle, hookType, topic, rank);
        String rationale = rationaleFor(hookType, chainType, score, sourceNodeIds);

        Map<String, Object> hookPlan = new LinkedHashMap<>();
        hookPlan.put("source", "deterministic_hook_generation_worker");
        hookPlan.put("rankIndex", rank);
        hookPlan.put("hookType", hookType);
        hookPlan.put("title", title);
        hookPlan.put("openingLine", openingLine);
        hookPlan.put("onScreenHook", onScreenHook);
        hookPlan.put("rationale", rationale);
        hookPlan.put("chainType", chainType);
        hookPlan.put("strategy", strategy);
        hookPlan.put("score", score);
        hookPlan.put("hookPriority", hookPriority(hookType, openingLine, firstText, payoffText));
        hookPlan.put("sourceNodeIds", sourceNodeIds);
        hookPlan.put("sourceStart", firstNumber(segments, "sourceStart"));
        hookPlan.put("sourceEnd", lastNumber(segments, "sourceEnd"));
        hookPlan.put("hookSourceText", truncate(firstText, 240));
        hookPlan.put("payoffSourceText", truncate(payoffText, 240));
        hookPlan.put("riskFlags", riskFlags);
        hookPlan.put("alternatives", alternatives(hookType, firstText, payoffText, topic));

        return new HookDecision(hookType, title, openingLine, onScreenHook, hookPlan, issues);
    }

    private void applyHook(Map<String, Object> candidate, HookDecision hook) {
        String previousTitle = stringValue(candidate.get("title"), "");
        String previousHookType = stringValue(candidate.get("hookType"), "");
        candidate.put("title", hook.title());
        candidate.put("hookType", hook.hookType());

        Map<String, Object> metadata = mapValue(candidate.get("metadata"));
        if (!previousTitle.isBlank() && !previousTitle.equals(hook.title())) {
            metadata.put("plannerTitle", previousTitle);
        }
        if (!previousHookType.isBlank() && !previousHookType.equals(hook.hookType())) {
            metadata.put("plannerHookType", previousHookType);
        }
        metadata.put("hookPlan", hook.hookPlan());
        metadata.put("hookSource", "deterministic_hook_generation_worker");
        metadata.put("hookOpeningLine", hook.openingLine());
        metadata.put("hookOnScreenText", hook.onScreenHook());
        candidate.put("metadata", metadata);

        Map<String, Object> renderManifest = mapValue(candidate.get("renderManifest"));
        renderManifest.putIfAbsent("hookOpeningLine", hook.openingLine());
        renderManifest.putIfAbsent("hookOnScreenText", hook.onScreenHook());
        candidate.put("renderManifest", renderManifest);
    }

    private Map<String, Object> candidateFromPlan(CreatorShortVideo video, Map<String, Object> plan) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("title", stringValue(plan.get("title"), video == null ? "Short candidate" : stringValue(video.getTitle(), "Short candidate")));
        candidate.put("durationSeconds", intValue(plan.get("durationSeconds"), video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds()));
        candidate.put("score", plan.getOrDefault("score", 90));
        candidate.put("hookType", stringValue(plan.get("hookType"), "Insight Hook"));
        candidate.put("editDecisionList", plan.getOrDefault("editDecisionList", Map.of()));
        candidate.put("metadata", plan.getOrDefault("metadata", Map.of()));
        return candidate;
    }

    private String hookTypeFor(String chainType, String strategy, String firstText, String payoffText, int score, int rank) {
        String combined = (chainType + " " + strategy + " " + firstText + " " + payoffText).toLowerCase(Locale.ROOT);
        if (combined.contains("?") || combined.matches(".*\\b(why|how|what|when|where|who)\\b.*")) {
            return "Question Hook";
        }
        if (combined.matches(".*\\b(strange|weird|mystery|hidden|secret|surprising|surprise|impossible|ancient|resilient|unexpected|what if)\\b.*")) {
            return "Curiosity Gap";
        }
        if (combined.matches(".*\\b(idea|concept|meaning|pattern|system|biology|evolution|abstract|philosophy|principle)\\b.*")) {
            return "Abstract Hook";
        }
        if (combined.matches(".*\\b(problem|mistake|issue|challenge|pain|struggle|wrong)\\b.*")) {
            return "Problem/Solution";
        }
        if (combined.matches(".*\\b(reveal|secret|truth|turns out|actually|finally|result)\\b.*")) {
            return "Reveal Hook";
        }
        if (combined.matches(".*\\b(but|however|instead|contrast|opposite)\\b.*")) {
            return "Contrarian Hook";
        }
        if (combined.matches(".*\\b(punchline|joke|funny|laugh)\\b.*")) {
            return "Punchline Hook";
        }
        if (score >= 82) {
            return "Aha Moment";
        }
        return rank % 2 == 0 ? "Aha Moment" : "Source Hook";
    }

    private String openingLineFor(String hookType, String firstText, String payoffText, String topic) {
        String source = truncate(nonBlank(firstText, payoffText, topic), 150);
        String normalized = source.toLowerCase(Locale.ROOT);
        if ("Question Hook".equals(hookType)) {
            if (source.contains("?")) {
                return source;
            }
            return "Why does this matter? " + lowerFirst(source);
        }
        if ("Curiosity Gap".equals(hookType)) {
            if (source.contains("?")) {
                return source;
            }
            return "The strange part is this: " + lowerFirst(source);
        }
        if ("Abstract Hook".equals(hookType)) {
            return "The bigger idea is this: " + lowerFirst(source);
        }
        if ("Problem/Solution".equals(hookType)) {
            if (normalized.matches(".*\\b(problem|mistake|issue|challenge|pain|struggle|wrong)\\b.*")) {
                return source;
            }
            return "The problem starts here: " + lowerFirst(source);
        }
        if ("Reveal Hook".equals(hookType)) {
            return "The reveal is this: " + lowerFirst(nonBlank(payoffText, firstText, topic));
        }
        if ("Contrarian Hook".equals(hookType)) {
            return "This sounds obvious, but " + lowerFirst(source);
        }
        if ("Punchline Hook".equals(hookType)) {
            return source;
        }
        if ("Aha Moment".equals(hookType)) {
            return "This is the moment it clicks: " + lowerFirst(source);
        }
        return source;
    }

    private String onScreenHook(String openingLine, String topic) {
        String text = nonBlank(openingLine, topic, "Watch this moment");
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() <= 56) {
            return text;
        }
        return truncate(text, 56).toUpperCase(Locale.ROOT);
    }

    private String titleFor(CreatorShortVideo video, String candidateTitle, String planTitle, String hookType, String topic, int rank) {
        String base = nonBlank(candidateTitle, planTitle, video == null ? "" : video.getTitle(), topic, "Short candidate");
        if (base.toLowerCase(Locale.ROOT).startsWith("candidate") || base.isBlank()) {
            base = topic;
        }
        String suffix = hookType.replace(" Hook", "").replace("/", " ");
        if (rank == 1) {
            return truncate(base, 180);
        }
        return truncate(base + " - " + suffix + " " + rank, 180);
    }

    private String rationaleFor(String hookType, String chainType, int score, List<String> sourceNodeIds) {
        return "%s selected for %s chain with score %d using source nodes %s.".formatted(
                hookType,
                chainType.toLowerCase(Locale.ROOT).replace('_', ' '),
                score,
                sourceNodeIds
        );
    }

    private List<String> alternatives(String hookType, String firstText, String payoffText, String topic) {
        List<String> alternatives = new ArrayList<>();
        alternatives.add(truncate(nonBlank(firstText, topic), 120));
        alternatives.add(truncate("Wait for this: " + lowerFirst(nonBlank(payoffText, firstText, topic)), 120));
        if ("Question Hook".equals(hookType)) {
            alternatives.add(truncate("The answer is hidden in this moment.", 120));
        } else if ("Abstract Hook".equals(hookType)) {
            alternatives.add(truncate("The bigger pattern is hiding here: " + lowerFirst(nonBlank(topic, firstText)), 120));
        } else {
            alternatives.add(truncate("Why " + lowerFirst(nonBlank(topic, firstText)), 120));
        }
        return alternatives.stream().filter(text -> !text.isBlank()).distinct().limit(3).toList();
    }

    private String hookPriority(String hookType, String openingLine, String firstText, String payoffText) {
        String combined = (hookType + " " + openingLine + " " + firstText + " " + payoffText).toLowerCase(Locale.ROOT);
        if (combined.contains("?") || combined.matches(".*\\b(why|how|what if|what|when|where|who)\\b.*")) {
            return "question_curiosity";
        }
        if (combined.matches(".*\\b(strange|mystery|hidden|secret|surprising|impossible|ancient|unexpected)\\b.*")) {
            return "curiosity_gap";
        }
        if (combined.matches(".*\\b(idea|concept|meaning|pattern|system|principle|biology|evolution)\\b.*")) {
            return "abstract_insight";
        }
        return "standard";
    }

    private List<String> riskFlags(String firstText, String payoffText, List<String> sourceNodeIds, Map<String, Object> storyCritic, Map<String, Object> interestingnessCritic) {
        List<String> flags = new ArrayList<>();
        String combined = (firstText + " " + payoffText).toLowerCase(Locale.ROOT);
        if (combined.matches(".*\\b(this|that|he|she|they|it)\\b.*") && !combined.matches(".*\\b(because|why|how|problem|solution|answer|result)\\b.*")) {
            flags.add("pronoun_context_risk");
        }
        List<String> unsafeNodes = unsafeHookNodes(storyCritic);
        if (sourceNodeIds.stream().anyMatch(unsafeNodes::contains)) {
            flags.add("story_critic_unsafe_hook_node");
        }
        List<String> rejectedWindows = listOfStrings(interestingnessCritic == null ? null : interestingnessCritic.get("rejectedWindowIds"));
        if (!rejectedWindows.isEmpty()) {
            flags.add("interestingness_critic_rejections_present");
        }
        return flags;
    }

    private List<String> unsafeHookNodes(Map<String, Object> storyCritic) {
        Map<String, Object> guidance = mapValue(storyCritic == null ? null : storyCritic.get("candidateGuidance"));
        List<String> result = new ArrayList<>(listOfStrings(guidance.get("unsafeHookNodes")));
        result.addAll(listOfStrings(guidance.get("avoidStandaloneNodes")));
        return result;
    }

    private String topic(CreatorShortVideo video, String firstText, String payoffText, String candidateTitle, String planTitle) {
        String title = nonBlank(candidateTitle, planTitle, video == null ? "" : video.getTitle());
        if (!title.isBlank() && !title.toLowerCase(Locale.ROOT).startsWith("candidate")) {
            return truncate(title, 120);
        }
        return truncate(nonBlank(firstText, payoffText, video == null ? "" : video.getOriginalFileName(), "this moment"), 120);
    }

    private String edlChainType(Map<String, Object> edl) {
        List<Map<String, Object>> chains = listOfMaps(edl.get("protectedChains"));
        if (!chains.isEmpty()) {
            return stringValue(chains.get(0).get("type"), "");
        }
        return stringValue(edl.get("strategy"), "");
    }

    private String firstSegmentText(List<Map<String, Object>> segments) {
        for (Map<String, Object> segment : segments) {
            String text = stringValue(segment.get("transcript"), "");
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String lastSegmentText(List<Map<String, Object>> segments) {
        for (int index = segments.size() - 1; index >= 0; index--) {
            String text = stringValue(segments.get(index).get("transcript"), "");
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private List<String> sourceNodeIds(List<Map<String, Object>> segments) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> segment : segments) {
            String nodeId = stringValue(segment.get("nodeId"), "");
            if (!nodeId.isBlank() && !result.contains(nodeId)) {
                result.add(nodeId);
            }
        }
        return result;
    }

    private Object firstNumber(List<Map<String, Object>> segments, String key) {
        for (Map<String, Object> segment : segments) {
            if (hasNumber(segment, key)) {
                return segment.get(key);
            }
        }
        return null;
    }

    private Object lastNumber(List<Map<String, Object>> segments, String key) {
        for (int index = segments.size() - 1; index >= 0; index--) {
            Map<String, Object> segment = segments.get(index);
            if (hasNumber(segment, key)) {
                return segment.get(key);
            }
        }
        return null;
    }

    private Map<String, Object> issue(String severity, String code, String summary) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.put("summary", summary);
        return issue;
    }

    private Map<String, Object> traceRow(String stage, String status, String summary, double confidence, Map<String, Object> metadata) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("status", status);
        row.put("summary", summary);
        row.put("confidence", Math.round(confidence * 1000.0d) / 1000.0d);
        row.put("agent", "hook-generation");
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("metadata", metadata == null ? Map.of() : metadata);
        return row;
    }

    private Object firstNonEmpty(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof String string && string.isBlank()) {
                continue;
            }
            if (value instanceof Map<?, ?> map && map.isEmpty()) {
                continue;
            }
            if (value instanceof List<?> list && list.isEmpty()) {
                continue;
            }
            return value;
        }
        return null;
    }

    private String nonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String lowerFirst(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return text;
        }
        return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
    }

    private boolean hasNumber(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number) {
            return true;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(String.valueOf(value));
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> value) {
        if (value == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : value) {
            result.add(item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item));
        }
        return result;
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    map.forEach((key, mapValue) -> {
                        if (key != null) {
                            normalized.put(String.valueOf(key), mapValue);
                        }
                    });
                    if (!normalized.isEmpty()) {
                        result.add(normalized);
                    }
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> {
                if (key != null) {
                    result.put(String.valueOf(key), mapValue);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    private List<String> listOfStrings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = stringValue(item, "");
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        } else {
            String text = stringValue(value, "");
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String truncate(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record HookDecision(
            String hookType,
            String title,
            String openingLine,
            String onScreenHook,
            Map<String, Object> hookPlan,
            List<Map<String, Object>> issues
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>(hookPlan);
            map.put("issues", issues);
            return map;
        }
    }

    public record HookGenerationResult(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> hooks,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
