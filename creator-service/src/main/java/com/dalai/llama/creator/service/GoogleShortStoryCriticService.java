package com.dalai.llama.creator.service;

import com.dalai.llama.creator.ai.GeminiRateLimitGuard;
import com.dalai.llama.creator.ai.GeminiUsageMetadataParser;
import com.dalai.llama.creator.ai.GoogleGenAiClientFactory;
import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

@Service
public class GoogleShortStoryCriticService {

    private static final Logger log = LoggerFactory.getLogger(GoogleShortStoryCriticService.class);
    private static final String PROMPT_TYPE = "SHORTS_STORY_CRITIC";
    private static final int RESPONSE_MAX_IN_MEMORY_BYTES = 24 * 1024 * 1024;
    private static final int MAX_TRANSCRIPT_PROMPT_CHARS = 26000;
    private static final int MAX_GRAPH_PROMPT_CHARS = 18000;
    private static final int MAX_CRITIC_CONTEXT_CHARS = 14000;

    private final CreatorProperties properties;
    private final CreatorAiPricingService pricingService;
    private final GeminiUsageMetadataParser usageMetadataParser;
    private final GoogleGenAiClientFactory googleGenAiClientFactory;
    private final GeminiRateLimitGuard geminiRateLimitGuard;
    private final ObjectMapper objectMapper;

    public GoogleShortStoryCriticService(
            CreatorProperties properties,
            CreatorAiPricingService pricingService,
            GeminiUsageMetadataParser usageMetadataParser,
            GoogleGenAiClientFactory googleGenAiClientFactory,
            GeminiRateLimitGuard geminiRateLimitGuard,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.pricingService = pricingService;
        this.usageMetadataParser = usageMetadataParser;
        this.googleGenAiClientFactory = googleGenAiClientFactory;
        this.geminiRateLimitGuard = geminiRateLimitGuard;
        this.objectMapper = objectMapper;
    }

    public StoryCriticResult critique(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> scenes,
            Map<String, Object> transcriptCritic,
            Map<String, Object> videoTypeCritic,
            Map<String, Object> sceneCritic
    ) {
        String model = stringValue(properties.getAi().getGeminiModel(), "gemini-2.5-flash");
        List<Map<String, Object>> safeTranscript = copyList(transcript);
        Map<String, Object> safeGraph = graph == null ? new LinkedHashMap<>() : new LinkedHashMap<>(graph);
        Map<String, Object> safeVideoDna = videoDna == null ? new LinkedHashMap<>() : new LinkedHashMap<>(videoDna);
        if (safeTranscript.isEmpty() && safeGraph.isEmpty()) {
            return skipped(model, "WARN", "Story critic could not run because transcript and graph are missing.");
        }
        try {
            String prompt = buildPrompt(video, safeVideoDna, safeTranscript, safeGraph, copyList(scenes), transcriptCritic, videoTypeCritic, sceneCritic);
            Map<String, Object> request = buildRequest(prompt);
            Map<String, Object> response = geminiRateLimitGuard.execute(PROMPT_TYPE, model, () ->
                    googleGenAiClientFactory.client(RESPONSE_MAX_IN_MEMORY_BYTES)
                            .post()
                            .uri(googleGenAiClientFactory.generateContentUri(model))
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                            })
                            .block(Duration.ofMillis(properties.getAi().getTimeoutMs()))
            );
            return normalizeResponse(model, prompt, response == null ? Map.of() : response, safeTranscript, safeGraph, safeVideoDna);
        } catch (Exception ex) {
            log.warn("Shorts story critic failed videoId={} message={}", video == null ? null : video.getId(), ex.getMessage());
            return skipped(model, "WARN", "Story critic failed: " + safeMessage(ex));
        }
    }
    private Map<String, Object> buildRequest(String prompt) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        if (properties.getAi().getMaxOutputTokens() != null && properties.getAi().getMaxOutputTokens() > 0) {
            generationConfig.put("maxOutputTokens", properties.getAi().getMaxOutputTokens());
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("systemInstruction", Map.of("parts", List.of(Map.of("text", "Return only one valid JSON object. Do not wrap it in markdown."))));
        request.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", prompt))
        )));
        request.put("generationConfig", generationConfig);
        return request;
    }

    private String buildPrompt(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> scenes,
            Map<String, Object> transcriptCritic,
            Map<String, Object> videoTypeCritic,
            Map<String, Object> sceneCritic
    ) {
        return """
                You are the independent STORY_CRITIC for a Generate Shorts backend pipeline.

                The planner has not selected short candidates yet. Your job is to independently understand the story from the real transcript and graph, then critique whether the story model is safe for compression. Do not generate shorts. Do not rubber-stamp the graph. Find missing context, broken causality, unresolved Q/A, weak setup-payoff, contradiction, emotional arc problems, and chains that must not be cut.

                Source metadata:
                {
                  "shortVideoId": "%s",
                  "title": "%s",
                  "originalFileName": "%s",
                  "platform": "%s",
                  "targetDurationSeconds": %s,
                  "requestedShorts": %s,
                  "videoDna": %s
                }

                Full transcript digest:
                %s

                Real graph digest:
                %s

                Scene digest:
                %s

                Upstream critic context:
                %s

                Return JSON only with this exact top-level shape:
                {
                  "storyUnderstanding": {
                    "centralThesis": "one sentence describing what the source is really about",
                    "audiencePromise": "what a viewer is promised if they keep watching",
                    "narrativeType": "qa|problem_solution|tutorial|reveal|debate|case_study|reaction|vlog|mixed|unknown",
                    "stakes": "what changes if the viewer understands this",
                    "payoff": "main reveal/result/takeaway",
                    "keyEntities": ["people, products, topics, places"],
                    "beatMap": [
                      {"beatId":"beat-001","role":"hook|setup|problem|context|turn|proof|answer|payoff|cta","nodeIds":["n-001"],"start":0.0,"end":5.0,"summary":"...","dependencyBeatIds":[]}
                    ],
                    "mustPreserveChains": [
                      {"chainId":"chain-001","type":"question_answer|problem_solution|cause_effect|setup_payoff|contrast|reveal","nodeIds":["n-001","n-002"],"reason":"why this chain cannot be split"}
                    ],
                    "removableContext": [
                      {"nodeIds":["n-010"],"reason":"why this can be trimmed safely"}
                    ]
                  },
                  "storyCritic": {
                    "status":"PASS|WARN|FAIL",
                    "confidence":0.0,
                    "coherenceScore":0,
                    "completenessScore":0,
                    "causalityScore":0,
                    "hookPayoffScore":0,
                    "contextRiskScore":0,
                    "compressionSafetyScore":0,
                    "issues":[],
                    "missingContext":[{"nodeId":"n-001","reason":"...","severity":"low|medium|high"}],
                    "brokenChains":[{"chainId":"chain-001","nodeIds":["n-001"],"reason":"...","severity":"low|medium|high"}],
                    "contradictions":[],
                    "preservationRules":[{"rule":"Keep answer with question","nodeIds":["n-001","n-002"],"reason":"..."}],
                    "repairActions":[{"action":"add_context|merge_chain|drop_segment|protect_nodes|reorder_candidate","nodeIds":["n-001"],"reason":"..."}],
                    "candidateGuidance": {
                      "goodHookNodes":["n-001"],
                      "unsafeHookNodes":["n-010"],
                      "bestPayoffNodes":["n-020"],
                      "avoidStandaloneNodes":["n-005"],
                      "recommendedStoryAngles":["..."]
                    },
                    "summary":"one sentence staff-level verdict"
                  }
                }

                Scoring guide:
                - PASS only when the story model is coherent enough for automated candidate generation.
                - WARN when candidates can be generated but must preserve chains or add context.
                - FAIL when the source story is too fragmented or graph/transcript evidence is unsafe for automated compression without repair.
                - contextRiskScore is high when cuts are likely to mislead, remove prerequisites, or separate question from answer.
                - compressionSafetyScore is high when the source has clear standalone chains suitable for shorts.
                """.formatted(
                video == null || video.getId() == null ? "" : video.getId(),
                video == null ? "" : escape(video.getTitle()),
                video == null ? "" : escape(video.getOriginalFileName()),
                video == null ? "" : escape(video.getPlatform()),
                video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds(),
                video == null || video.getRequestedShorts() == null ? 20 : video.getRequestedShorts(),
                toJson(videoDna),
                compact(toJson(transcriptDigest(transcript)), MAX_TRANSCRIPT_PROMPT_CHARS),
                compact(toJson(graphDigest(graph)), MAX_GRAPH_PROMPT_CHARS),
                compact(toJson(sceneDigest(scenes)), 9000),
                compact(toJson(criticContext(transcriptCritic, videoTypeCritic, sceneCritic)), MAX_CRITIC_CONTEXT_CHARS)
        );
    }
    private Map<String, Object> transcriptDigest(List<Map<String, Object>> transcript) {
        Map<String, Object> digest = new LinkedHashMap<>();
        List<Map<String, Object>> safeTranscript = copyList(transcript);
        digest.put("nodeCount", safeTranscript.size());
        digest.put("durationSeconds", round3(transcriptDuration(safeTranscript)));
        digest.put("speakerCount", safeTranscript.stream().map(node -> stringValue(node.get("speaker"), "Speaker 1")).distinct().count());
        digest.put("sampledNodes", sampledTranscriptNodes(safeTranscript));
        digest.put("interestingNodes", interestingNodes(safeTranscript));
        return digest;
    }

    private List<Map<String, Object>> sampledTranscriptNodes(List<Map<String, Object>> transcript) {
        List<Map<String, Object>> sampled = new ArrayList<>();
        TreeSet<Integer> indexes = sampledIndexes(transcript == null ? 0 : transcript.size(), 28);
        for (Integer index : indexes) {
            Map<String, Object> source = transcript.get(index);
            Map<String, Object> node = compactTranscriptNode(source, index);
            sampled.add(node);
        }
        return sampled;
    }

    private List<Map<String, Object>> interestingNodes(List<Map<String, Object>> transcript) {
        return (transcript == null ? List.<Map<String, Object>>of() : transcript).stream()
                .sorted((left, right) -> Integer.compare(interestingness(right), interestingness(left)))
                .limit(16)
                .map(node -> compactTranscriptNode(node, 0))
                .toList();
    }

    private Map<String, Object> compactTranscriptNode(Map<String, Object> source, int index) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", source.getOrDefault("id", "n-" + (index + 1)));
        node.put("start", source.getOrDefault("start", 0));
        node.put("end", source.getOrDefault("end", 0));
        node.put("speaker", source.getOrDefault("speaker", "Speaker 1"));
        node.put("sceneId", source.getOrDefault("sceneId", ""));
        node.put("interestingness", source.getOrDefault("interestingness", interestingness(source)));
        node.put("transcript", compact(stringValue(source.get("transcript"), ""), 900));
        return node;
    }

    private Map<String, Object> graphDigest(Map<String, Object> graph) {
        Map<String, Object> digest = new LinkedHashMap<>();
        Map<String, Object> safeGraph = graph == null ? new LinkedHashMap<>() : graph;
        digest.put("analysisSource", safeGraph.getOrDefault("analysisSource", ""));
        digest.put("metadata", safeGraph.getOrDefault("metadata", Map.of()));
        digest.put("storyGraph", safeGraph.getOrDefault("storyGraph", Map.of()));
        digest.put("conversationGraph", trimGraphView(mapValue(safeGraph.get("conversationGraph")), 40));
        digest.put("compressionGraph", trimGraphView(mapValue(safeGraph.get("compressionGraph")), 40));
        digest.put("edgeSummary", edgeSummary(listOfMaps(safeGraph.get("edges"))));
        digest.put("sampledEdges", listOfMaps(safeGraph.get("edges")).stream().limit(80).toList());
        return digest;
    }

    private Map<String, Object> trimGraphView(Map<String, Object> view, int limit) {
        Map<String, Object> copy = new LinkedHashMap<>(view);
        for (String key : List.of("nodes", "edges", "anchors", "protectedChains")) {
            Object value = copy.get(key);
            if (value instanceof List<?> list && list.size() > limit) {
                copy.put(key, new ArrayList<>(list.subList(0, limit)));
                copy.put(key + "Truncated", true);
            }
        }
        return copy;
    }

    private Map<String, Object> edgeSummary(List<Map<String, Object>> edges) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            String type = stringValue(edge.get("type"), "unknown");
            counts.put(type, intValue(counts.get(type), 0) + 1);
        }
        return counts;
    }

    private Map<String, Object> sceneDigest(List<Map<String, Object>> scenes) {
        Map<String, Object> digest = new LinkedHashMap<>();
        List<Map<String, Object>> sampled = new ArrayList<>();
        List<Map<String, Object>> safeScenes = copyList(scenes);
        for (Integer index : sampledIndexes(safeScenes.size(), 18)) {
            Map<String, Object> source = safeScenes.get(index);
            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("id", source.getOrDefault("id", "scene-" + (index + 1)));
            scene.put("start", source.getOrDefault("start", 0));
            scene.put("end", source.getOrDefault("end", 0));
            scene.put("durationSeconds", source.getOrDefault("durationSeconds", 0));
            scene.put("frameIds", source.getOrDefault("frameIds", List.of()));
            sampled.add(scene);
        }
        digest.put("sceneCount", safeScenes.size());
        digest.put("sampledScenes", sampled);
        return digest;
    }

    private Map<String, Object> criticContext(Map<String, Object> transcriptCritic, Map<String, Object> videoTypeCritic, Map<String, Object> sceneCritic) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("transcriptCritic", compactCritic(transcriptCritic));
        context.put("videoTypeCritic", compactCritic(videoTypeCritic));
        context.put("sceneCritic", compactCritic(sceneCritic));
        return context;
    }

    private Map<String, Object> compactCritic(Map<String, Object> critic) {
        Map<String, Object> copy = critic == null ? new LinkedHashMap<>() : new LinkedHashMap<>(critic);
        copy.remove("rawTextPreview");
        copy.remove("chunkAudits");
        copy.remove("sceneAudits");
        copy.remove("transitionAudits");
        copy.remove("sampleAudits");
        return copy;
    }

    private TreeSet<Integer> sampledIndexes(int size, int limit) {
        TreeSet<Integer> indexes = new TreeSet<>();
        if (size <= 0) {
            return indexes;
        }
        for (int index = 0; index < Math.min(5, size); index++) indexes.add(index);
        for (int index = Math.max(0, size - 5); index < size; index++) indexes.add(index);
        if (size > 10) {
            indexes.add(Math.max(0, size / 4));
            indexes.add(Math.max(0, size / 2));
            indexes.add(Math.max(0, (size * 3) / 4));
        }
        int step = Math.max(1, size / Math.max(1, limit));
        for (int index = 0; index < size && indexes.size() < limit; index += step) {
            indexes.add(index);
        }
        while (indexes.size() > limit) {
            indexes.pollLast();
        }
        return indexes;
    }
    private StoryCriticResult normalizeResponse(
            String model,
            String prompt,
            Map<String, Object> response,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            Map<String, Object> videoDna
    ) {
        String rawText = outputText(response);
        Map<String, Object> output = parseJsonObject(rawText);
        Map<String, Object> usage = usageMetadataParser.parse(response.get("usageMetadata"));
        long fallbackInputTokens = pricingService.estimateTextTokens(prompt);
        long providerInputTokens = longValue(usage.get("inputTokens"));
        long inputTokens = Math.max(providerInputTokens, fallbackInputTokens);
        long outputTokens = longValue(usage.get("outputTokens"));
        String usageSource = providerInputTokens > 0 || outputTokens > 0 ? "PROVIDER" : "ESTIMATED_TEXT_ONLY";

        Map<String, Object> tokenMetadata = new LinkedHashMap<>(usage);
        tokenMetadata.put("inputTokens", inputTokens);
        tokenMetadata.put("outputTokens", outputTokens);
        tokenMetadata.put("totalTokens", Math.max(longValue(usage.get("totalTokens")), inputTokens + outputTokens));
        tokenMetadata.put("source", usageSource);

        Map<String, Object> costMetadata = pricingService.estimateTextCall("gemini", model, PROMPT_TYPE, inputTokens, outputTokens, usageSource);
        costMetadata.put("provider", "gemini");
        costMetadata.put("model", model);
        costMetadata.put("operation", PROMPT_TYPE);
        costMetadata.put("googleGenaiBackend", googleGenAiClientFactory.backend());

        Map<String, Object> storyUnderstanding = normalizeStoryUnderstanding(mapValue(output.get("storyUnderstanding")), transcript, videoDna);
        Map<String, Object> storyCritic = normalizeStoryCritic(mapValue(output.get("storyCritic")));
        String status = normalizeStatus(storyCritic.get("status"));
        double confidence = clamp(doubleValue(storyCritic.get("confidence"), 0.0), 0.0, 1.0);

        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceRow(
                "STORY_UNDERSTANDING",
                storyUnderstanding.isEmpty() ? "WARN" : "COMPLETED",
                defaultString(storyUnderstanding.get("centralThesis"), "Story understanding was generated from transcript and graph."),
                storyUnderstanding.isEmpty() ? 0.25 : Math.max(0.35, confidence),
                Map.of(
                        "source", "gemini_story_critic",
                        "narrativeType", storyUnderstanding.getOrDefault("narrativeType", "unknown"),
                        "beatCount", listValue(storyUnderstanding.get("beatMap")).size(),
                        "chainCount", listValue(storyUnderstanding.get("mustPreserveChains")).size()
                )
        ));
        trace.add(traceRow(
                "STORY_CRITIC",
                status,
                stringValue(storyCritic.get("summary"), "Story critic completed."),
                confidence,
                Map.of(
                        "source", "gemini_story_critic",
                        "coherenceScore", storyCritic.get("coherenceScore"),
                        "completenessScore", storyCritic.get("completenessScore"),
                        "causalityScore", storyCritic.get("causalityScore"),
                        "contextRiskScore", storyCritic.get("contextRiskScore"),
                        "compressionSafetyScore", storyCritic.get("compressionSafetyScore")
                )
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_story_critic");
        metadata.put("status", status);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("model", model);
        metadata.put("transcriptNodeCount", transcript.size());
        metadata.put("graphNodeCount", listOfMaps(graph.get("nodes")).size());
        metadata.put("graphEdgeCount", listOfMaps(graph.get("edges")).size());
        metadata.put("storyUnderstanding", storyUnderstanding);
        metadata.put("storyCritic", storyCritic);
        metadata.put("rawTextPreview", compact(rawText, 4000));

        return new StoryCriticResult(true, "gemini", model, storyUnderstanding, storyCritic, trace, tokenMetadata, costMetadata, metadata);
    }

    private Map<String, Object> normalizeStoryUnderstanding(Map<String, Object> value, List<Map<String, Object>> transcript, Map<String, Object> videoDna) {
        Map<String, Object> result = new LinkedHashMap<>(value);
        result.putIfAbsent("centralThesis", fallbackThesis(transcript));
        result.putIfAbsent("audiencePromise", "Clear takeaway from the source video.");
        result.putIfAbsent("narrativeType", normalizeNarrativeType(result.getOrDefault("narrativeType", videoDna.get("structureType"))));
        result.putIfAbsent("stakes", "The viewer needs enough context to understand the payoff.");
        result.putIfAbsent("payoff", fallbackPayoff(transcript));
        result.putIfAbsent("keyEntities", List.of());
        result.putIfAbsent("beatMap", fallbackBeatMap(transcript));
        result.putIfAbsent("mustPreserveChains", List.of());
        result.putIfAbsent("removableContext", List.of());
        return result;
    }

    private Map<String, Object> normalizeStoryCritic(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>(value);
        result.put("status", normalizeStatus(result.get("status")));
        result.put("confidence", clamp(doubleValue(result.get("confidence"), 0.0), 0.0, 1.0));
        for (String key : List.of("coherenceScore", "completenessScore", "causalityScore", "hookPayoffScore", "contextRiskScore", "compressionSafetyScore")) {
            result.put(key, clampInt(intValue(result.get(key), 0), 0, 100));
        }
        result.putIfAbsent("issues", List.of());
        result.putIfAbsent("missingContext", List.of());
        result.putIfAbsent("brokenChains", List.of());
        result.putIfAbsent("contradictions", List.of());
        result.putIfAbsent("preservationRules", List.of());
        result.putIfAbsent("repairActions", List.of());
        result.putIfAbsent("candidateGuidance", Map.of("goodHookNodes", List.of(), "unsafeHookNodes", List.of(), "bestPayoffNodes", List.of(), "avoidStandaloneNodes", List.of(), "recommendedStoryAngles", List.of()));
        result.putIfAbsent("summary", "Story critic completed.");
        return result;
    }

    private StoryCriticResult skipped(String model, String status, String reason) {
        Map<String, Object> storyUnderstanding = new LinkedHashMap<>();
        storyUnderstanding.put("centralThesis", "Story unavailable.");
        storyUnderstanding.put("narrativeType", "unknown");
        storyUnderstanding.put("beatMap", List.of());
        storyUnderstanding.put("mustPreserveChains", List.of());

        Map<String, Object> storyCritic = normalizeStoryCritic(new LinkedHashMap<>());
        storyCritic.put("status", status);
        storyCritic.put("summary", reason);
        storyCritic.put("issues", List.of(reason));

        Map<String, Object> tokenMetadata = new LinkedHashMap<>();
        tokenMetadata.put("inputTokens", 0);
        tokenMetadata.put("outputTokens", 0);
        tokenMetadata.put("totalTokens", 0);
        tokenMetadata.put("source", "NONE");
        Map<String, Object> costMetadata = pricingService.estimateTextCall("gemini", model, PROMPT_TYPE, 0, 0, "NONE");
        costMetadata.put("provider", "gemini");
        costMetadata.put("model", model);
        costMetadata.put("operation", PROMPT_TYPE);

        List<Map<String, Object>> trace = List.of(
                traceRow("STORY_UNDERSTANDING", "WARN", "Story understanding could not be generated independently.", 0.2, Map.of("source", "gemini_story_critic")),
                traceRow("STORY_CRITIC", status, reason, 0.2, Map.of("source", "gemini_story_critic"))
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "gemini_story_critic");
        metadata.put("status", status);
        metadata.put("reason", reason);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("storyUnderstanding", storyUnderstanding);
        metadata.put("storyCritic", storyCritic);
        return new StoryCriticResult(false, "gemini", model, storyUnderstanding, storyCritic, trace, tokenMetadata, costMetadata, metadata);
    }
    private List<Map<String, Object>> fallbackBeatMap(List<Map<String, Object>> transcript) {
        List<Map<String, Object>> beats = new ArrayList<>();
        int size = transcript == null ? 0 : transcript.size();
        int index = 0;
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            if (index >= 24) {
                break;
            }
            Map<String, Object> beat = new LinkedHashMap<>();
            beat.put("beatId", "beat-%03d".formatted(index + 1));
            beat.put("role", beatRole(index, size, stringValue(node.get("transcript"), "")));
            beat.put("nodeIds", List.of(stringValue(node.get("id"), "n-" + (index + 1))));
            beat.put("start", node.getOrDefault("start", 0));
            beat.put("end", node.getOrDefault("end", 0));
            beat.put("summary", compact(stringValue(node.get("transcript"), ""), 180));
            beat.put("dependencyBeatIds", index == 0 ? List.of() : List.of("beat-%03d".formatted(index)));
            beats.add(beat);
            index++;
        }
        return beats;
    }

    private String fallbackThesis(List<Map<String, Object>> transcript) {
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            String text = stringValue(node.get("transcript"), "").trim();
            if (text.length() > 20) {
                return compact(text, 180);
            }
        }
        return "The source video contains a sequence of creator statements that need story-safe compression.";
    }

    private String fallbackPayoff(List<Map<String, Object>> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return "No payoff identified.";
        }
        for (int index = transcript.size() - 1; index >= 0; index--) {
            String text = stringValue(transcript.get(index).get("transcript"), "").trim();
            if (text.length() > 20) {
                return compact(text, 180);
            }
        }
        return "No payoff identified.";
    }

    private String beatRole(int index, int count, String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (normalized.contains("?")) return "hook";
        if (normalized.matches(".*\\b(problem|mistake|issue|challenge|pain)\\b.*")) return "problem";
        if (normalized.matches(".*\\b(solution|fix|answer|solve)\\b.*")) return "answer";
        if (normalized.matches(".*\\b(result|therefore|finally|payoff)\\b.*")) return "payoff";
        if (count <= 1 || index == 0) return "setup";
        if (index >= Math.max(0, count - 2)) return "payoff";
        return "context";
    }

    private String normalizeNarrativeType(Object value) {
        String type = stringValue(value, "unknown").trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (type) {
            case "qa", "problem_solution", "tutorial", "reveal", "debate", "case_study", "reaction", "vlog", "mixed" -> type;
            case "instructional" -> "tutorial";
            case "storytelling", "dramatic" -> "mixed";
            default -> "unknown";
        };
    }

    private int interestingness(Map<String, Object> node) {
        Object existing = node.get("interestingness");
        if (existing instanceof Number number && number.intValue() > 0) {
            return clampInt(number.intValue(), 0, 100);
        }
        String text = stringValue(node.get("transcript"), "").toLowerCase(Locale.ROOT);
        int score = 45;
        if (text.contains("?")) score += 12;
        if (text.matches(".*\\b(secret|mistake|problem|why|how|result|truth|money|growth|staff|customer|story)\\b.*")) score += 16;
        if (text.length() > 80) score += 8;
        if (text.length() < 15) score -= 8;
        return clampInt(score, 0, 100);
    }

    private double transcriptDuration(List<Map<String, Object>> transcript) {
        double duration = 0.0;
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            duration = Math.max(duration, doubleValue(node.get("end"), 0.0));
        }
        return duration;
    }

    private Map<String, Object> traceRow(String stage, String status, String summary, double confidence, Map<String, Object> metadata) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("status", status);
        row.put("summary", summary);
        row.put("confidence", confidence);
        row.put("agent", stage.toLowerCase(Locale.ROOT).replace('_', '-'));
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("metadata", metadata == null ? Map.of() : metadata);
        return row;
    }

    private String outputText(Map<String, Object> response) {
        Object candidates = response.get("candidates");
        if (candidates instanceof List<?> candidateItems) {
            StringBuilder text = new StringBuilder();
            for (Object candidateItem : candidateItems) {
                Map<String, Object> candidate = mapValue(candidateItem);
                Map<String, Object> content = mapValue(candidate.get("content"));
                Object parts = content.get("parts");
                if (parts instanceof List<?> partItems) {
                    for (Object partItem : partItems) {
                        Object partText = mapValue(partItem).get("text");
                        if (partText != null && !String.valueOf(partText).isBlank()) text.append(partText);
                    }
                }
            }
            return text.toString();
        }
        return "";
    }

    private Map<String, Object> parseJsonObject(String outputText) {
        if (outputText == null || outputText.isBlank()) return new LinkedHashMap<>();
        String cleaned = stripJsonFence(outputText.trim());
        try {
            return objectMapper.readValue(cleaned, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ex) {
            String objectJson = firstBalancedJsonObject(cleaned);
            if (!objectJson.isBlank()) {
                try {
                    return objectMapper.readValue(objectJson, new TypeReference<LinkedHashMap<String, Object>>() {
                    });
                } catch (Exception ignored) {
                    return new LinkedHashMap<>();
                }
            }
            return new LinkedHashMap<>();
        }
    }
    private String firstBalancedJsonObject(String text) {
        int start = text == null ? -1 : text.indexOf('{');
        if (start < 0) return "";
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (character == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (character == '{') depth++;
            if (character == '}') {
                depth--;
                if (depth == 0) return text.substring(start, index + 1);
            }
        }
        return "";
    }

    private String stripJsonFence(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[A-Za-z0-9_-]*\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        return cleaned.trim();
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) normalized.put(String.valueOf(key), item);
            });
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = mapValue(item);
                if (!map.isEmpty()) result.add(map);
            }
        }
        return result;
    }

    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) return new ArrayList<>(list);
        if (value == null || String.valueOf(value).isBlank()) return new ArrayList<>();
        return new ArrayList<>(List.of(String.valueOf(value)));
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> value) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (value == null) return copy;
        for (Map<String, Object> item : value) {
            copy.add(item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item));
        }
        return copy;
    }

    private String normalizeStatus(Object value) {
        String status = stringValue(value, "WARN").trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "PASS", "PASSED", "COMPLETED", "OK" -> "PASS";
            case "FAIL", "FAILED", "ERROR" -> "FAIL";
            default -> "WARN";
        };
    }

    private long longValue(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Math.max(0, Long.parseLong(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private String compact(String value, int maxLength) {
        String safe = value == null ? "" : value;
        if (safe.length() <= maxLength) return safe;
        return safe.substring(0, Math.max(0, maxLength)) + "...";
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String defaultString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String safeMessage(Exception ex) {
        return ex == null || ex.getMessage() == null ? "unknown error" : ex.getMessage();
    }

    private String escape(String value) {
        if (value == null) return "";
        try {
            return objectMapper.writeValueAsString(value).replaceAll("^\"|\"$", "");
        } catch (JsonProcessingException ex) {
            return value;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    public record StoryCriticResult(
            boolean mediaBacked,
            String provider,
            String model,
            Map<String, Object> storyUnderstanding,
            Map<String, Object> storyCritic,
            List<Map<String, Object>> trace,
            Map<String, Object> tokenMetadata,
            Map<String, Object> costMetadata,
            Map<String, Object> metadata
    ) {
    }
}