package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ShortCompressionPlannerService {

    private static final Set<String> PROTECTED_CHAIN_TYPES = Set.of(
            "QUESTION_ANSWER",
            "PROBLEM_SOLUTION",
            "CAUSE_EFFECT",
            "ESCALATION",
            "CONTRAST"
    );

    public CompressionPlanningResult plan(CreatorShortVideo video, List<Map<String, Object>> transcript, Map<String, Object> graph) {
        return plan(video, transcript, graph, null, null);
    }

    public CompressionPlanningResult plan(
            CreatorShortVideo video,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            ShortInterestingnessScoringService.InterestingnessScoringResult interestingnessScoring
    ) {
        return plan(video, transcript, graph, interestingnessScoring, null);
    }

    public CompressionPlanningResult plan(
            CreatorShortVideo video,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            ShortInterestingnessScoringService.InterestingnessScoringResult interestingnessScoring,
            ShortInterestingnessCriticService.InterestingnessCriticResult interestingnessCritic
    ) {
        List<Map<String, Object>> nodes = copyList(transcript);
        nodes.sort(Comparator.comparingDouble(node -> doubleValue(node.get("start"), 0.0)));
        int requested = Math.max(1, Math.min(video == null || video.getRequestedShorts() == null ? 20 : video.getRequestedShorts(), 50));
        int targetDuration = Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());

        List<ChainSeed> seeds = interestingnessSeeds(nodes, interestingnessScoring, interestingnessCritic);
        seeds.addAll(chainSeeds(nodes, graph));
        seeds = uniqueSeeds(seeds);
        if (seeds.isEmpty()) {
            seeds.addAll(interestingSeeds(nodes));
        }
        if (seeds.isEmpty() && !nodes.isEmpty()) {
            seeds.add(new ChainSeed("SEQUENTIAL", List.of(stringValue(nodes.get(0).get("id"), "")), "Start from first transcript node."));
        }

        List<Map<String, Object>> plans = new ArrayList<>();
        for (int index = 0; index < requested; index++) {
            ChainSeed seed = seeds.isEmpty()
                    ? new ChainSeed("SEQUENTIAL", List.of(), "No transcript seed available.")
                    : seeds.get(index % seeds.size());
            plans.add(planForSeed(video, nodes, seed, index + 1, targetDuration));
        }

        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceRow(
                "COMPRESSION",
                plans.isEmpty() ? "WARN" : "COMPLETED",
                plans.isEmpty()
                        ? "No compression edit decisions were generated because transcript nodes are missing."
                        : "Generated exact timestamp edit decision lists while preserving semantic chains.",
                plans.isEmpty() ? 0.25 : 0.86,
                Map.of(
                        "planCount", plans.size(),
                        "targetDurationSeconds", targetDuration,
                        "seedCount", seeds.size(),
                        "interestingnessWindowCount", interestingnessScoring == null ? 0 : interestingnessScoring.candidateWindows().size(),
                        "interestingnessAcceptedWindowCount", acceptedWindowIds(interestingnessCritic).size(),
                        "interestingnessCriticStatus", interestingnessCritic == null ? "NOT_RUN" : interestingnessCritic.status(),
                        "source", "deterministic_compression_worker"
                )
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "deterministic_compression_worker");
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("planCount", plans.size());
        metadata.put("targetDurationSeconds", targetDuration);
        metadata.put("transcriptNodeCount", nodes.size());
        metadata.put("seedCount", seeds.size());
        metadata.put("interestingnessWindowCount", interestingnessScoring == null ? 0 : interestingnessScoring.candidateWindows().size());
        metadata.put("interestingnessAcceptedWindowCount", acceptedWindowIds(interestingnessCritic).size());
        metadata.put("interestingnessCriticStatus", interestingnessCritic == null ? "NOT_RUN" : interestingnessCritic.status());

        return new CompressionPlanningResult(plans, trace, metadata);
    }

    private Map<String, Object> planForSeed(CreatorShortVideo video, List<Map<String, Object>> nodes, ChainSeed seed, int rank, int targetDuration) {
        List<Map<String, Object>> selected = selectedNodes(nodes, seed, targetDuration);
        List<Map<String, Object>> segments = new ArrayList<>();
        double timelineCursor = 0.0;
        double actualDuration = 0.0;
        Set<String> seedNodeIds = new HashSet<>(seed.nodeIds());

        for (Map<String, Object> node : selected) {
            double sourceStart = doubleValue(node.get("start"), 0.0);
            double sourceEnd = Math.max(sourceStart + 0.1, doubleValue(node.get("end"), sourceStart + 0.1));
            double sourceDuration = sourceEnd - sourceStart;
            double remaining = Math.max(0.0, targetDuration - actualDuration);
            boolean protectedNode = seedNodeIds.contains(stringValue(node.get("id"), ""));
            if (remaining <= 0.0 && !protectedNode) {
                continue;
            }
            if (sourceDuration > remaining && remaining >= 1.5) {
                sourceEnd = sourceStart + remaining;
                sourceDuration = remaining;
            } else if (sourceDuration > remaining && !protectedNode) {
                continue;
            }

            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("nodeId", stringValue(node.get("id"), ""));
            segment.put("sceneId", stringValue(node.get("sceneId"), ""));
            segment.put("sourceStart", round3(sourceStart));
            segment.put("sourceEnd", round3(sourceEnd));
            segment.put("timelineStart", round3(timelineCursor));
            segment.put("timelineEnd", round3(timelineCursor + sourceDuration));
            segment.put("durationSeconds", round3(sourceDuration));
            segment.put("operation", protectedNode ? operationFor(seed.chainType()) : "KEEP_CONTEXT");
            segment.put("chainType", seed.chainType());
            segment.put("reason", protectedNode ? seed.reason() : "Context/payoff added because it fits the target duration.");
            segment.put("transcript", stringValue(node.get("transcript"), ""));
            segment.put("frames", node.getOrDefault("frames", List.of()));
            segments.add(segment);
            timelineCursor += sourceDuration;
            actualDuration += sourceDuration;
        }

        Map<String, Object> protectedChain = new LinkedHashMap<>();
        protectedChain.put("type", seed.chainType());
        protectedChain.put("nodeIds", seed.nodeIds());
        protectedChain.put("reason", seed.reason());

        Map<String, Object> edl = new LinkedHashMap<>();
        edl.put("version", 2);
        edl.put("source", "deterministic_compression_worker");
        edl.put("exactTimestampEdl", true);
        edl.put("strategy", operationFor(seed.chainType()));
        edl.put("rankIndex", rank);
        edl.put("targetDurationSeconds", targetDuration);
        edl.put("actualDurationSeconds", round3(actualDuration));
        edl.put("timelineStart", 0);
        edl.put("timelineEnd", round3(actualDuration));
        edl.put("segments", segments);
        edl.put("protectedChains", List.of(protectedChain));
        edl.put("selectionReason", seed.reason());
        edl.put("renderPolicy", "concat_source_segments_in_timeline_order");
        edl.put("generatedAt", OffsetDateTime.now().toString());

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("rankIndex", rank);
        plan.put("candidateKey", "compression-%03d".formatted(rank));
        plan.put("hookType", hookTypeFor(seed.chainType(), rank));
        plan.put("chainType", seed.chainType());
        plan.put("score", planScore(seed, rank));
        plan.put("title", titleFor(video, seed, rank));
        plan.put("durationSeconds", Math.max(1, (int) Math.round(actualDuration)));
        plan.put("editDecisionList", edl);
        plan.put("metadata", Map.of(
                "compressionSource", "deterministic_compression_worker",
                "chain", chainLabel(seed.chainType()),
                "selectionReason", seed.reason(),
                "interestingnessScore", seed.score()
        ));
        return plan;
    }

    private List<Map<String, Object>> selectedNodes(List<Map<String, Object>> nodes, ChainSeed seed, int targetDuration) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String id = stringValue(node.get("id"), "");
            if (!id.isBlank()) {
                byId.put(id, node);
            }
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        for (String nodeId : seed.nodeIds()) {
            Map<String, Object> node = byId.get(nodeId);
            if (node != null && !containsNode(selected, nodeId)) {
                selected.add(node);
            }
        }
        selected.sort(Comparator.comparingDouble(node -> doubleValue(node.get("start"), 0.0)));

        if (selected.isEmpty()) {
            return selected;
        }

        int firstIndex = nodes.indexOf(selected.get(0));
        int lastIndex = nodes.indexOf(selected.get(selected.size() - 1));
        double duration = duration(selected);

        if (firstIndex > 0 && duration < targetDuration * 0.70) {
            Map<String, Object> previous = nodes.get(firstIndex - 1);
            if (fits(duration, previous, targetDuration)) {
                selected.add(previous);
                duration = duration(selected);
            }
        }
        if (lastIndex >= 0 && lastIndex < nodes.size() - 1 && duration < targetDuration * 0.92) {
            Map<String, Object> next = nodes.get(lastIndex + 1);
            if (fits(duration, next, targetDuration)) {
                selected.add(next);
            }
        }

        selected.sort(Comparator.comparingDouble(node -> doubleValue(node.get("start"), 0.0)));
        return selected;
    }

    private boolean fits(double currentDuration, Map<String, Object> node, int targetDuration) {
        return currentDuration + nodeDuration(node) <= targetDuration + 0.75;
    }

    private boolean containsNode(List<Map<String, Object>> nodes, String nodeId) {
        for (Map<String, Object> node : nodes) {
            if (nodeId.equals(stringValue(node.get("id"), ""))) {
                return true;
            }
        }
        return false;
    }

    private List<ChainSeed> interestingnessSeeds(
            List<Map<String, Object>> transcript,
            ShortInterestingnessScoringService.InterestingnessScoringResult scoring,
            ShortInterestingnessCriticService.InterestingnessCriticResult critic
    ) {
        List<ChainSeed> seeds = new ArrayList<>();
        if (scoring == null || !criticAllowsInterestingnessSeeds(critic)) {
            return seeds;
        }
        Set<String> transcriptNodeIds = new HashSet<>();
        for (Map<String, Object> node : transcript) {
            String id = stringValue(node.get("id"), "");
            if (!id.isBlank()) {
                transcriptNodeIds.add(id);
            }
        }
        Set<String> acceptedWindowIds = new HashSet<>(acceptedWindowIds(critic));
        boolean filterByAcceptedWindows = critic != null && !acceptedWindowIds.isEmpty();
        for (Map<String, Object> window : copyList(scoring.candidateWindows())) {
            String windowId = stringValue(window.get("windowId"), "");
            if (filterByAcceptedWindows && !acceptedWindowIds.contains(windowId)) {
                continue;
            }
            List<String> nodeIds = listOfStrings(window.get("nodeIds")).stream()
                    .filter(transcriptNodeIds::contains)
                    .toList();
            if (nodeIds.isEmpty()) {
                continue;
            }
            int score = Math.max(0, Math.min(100, intValue(window.get("score"), averageInterestingness(transcript, nodeIds))));
            seeds.add(new ChainSeed(
                    stringValue(window.get("chainType"), "INTERESTINGNESS"),
                    nodeIds,
                    stringValue(window.get("reason"), "Ranked by robust interestingness worker."),
                    score
            ));
        }
        return uniqueSeeds(seeds);
    }

    private boolean criticAllowsInterestingnessSeeds(ShortInterestingnessCriticService.InterestingnessCriticResult critic) {
        if (critic == null) {
            return true;
        }
        if ("FAIL".equalsIgnoreCase(critic.status())) {
            return false;
        }
        Map<String, Object> payload = critic.interestingnessCritic() == null ? Map.of() : critic.interestingnessCritic();
        Map<String, Object> guidance = mapValue(payload.get("guidance"));
        Object useForCompression = guidance.get("useForCompression");
        return !Boolean.FALSE.equals(useForCompression);
    }

    private List<String> acceptedWindowIds(ShortInterestingnessCriticService.InterestingnessCriticResult critic) {
        if (critic == null || critic.interestingnessCritic() == null) {
            return List.of();
        }
        return listOfStrings(critic.interestingnessCritic().get("acceptedWindowIds"));
    }

    private List<ChainSeed> chainSeeds(List<Map<String, Object>> transcript, Map<String, Object> graph) {
        List<ChainSeed> seeds = new ArrayList<>();
        List<Map<String, Object>> edges = listOfMaps(graph == null ? null : graph.get("edges"));
        for (Map<String, Object> edge : edges) {
            String type = stringValue(edge.get("type"), "SEQUENTIAL");
            if (!PROTECTED_CHAIN_TYPES.contains(type)) {
                continue;
            }
            String from = stringValue(edge.get("from"), "");
            String to = stringValue(edge.get("to"), "");
            if (from.isBlank() || to.isBlank() || from.startsWith("scene-") || to.startsWith("scene-")) {
                continue;
            }
            seeds.add(new ChainSeed(type, List.of(from, to), stringValue(edge.get("reason"), "Protected semantic chain.")));
        }
        for (Map<String, Object> node : transcript) {
            String text = stringValue(node.get("transcript"), "");
            String type = detectedStandaloneType(text);
            if (!type.isBlank()) {
                String nodeId = stringValue(node.get("id"), "");
                if (!nodeId.isBlank()) {
                    seeds.add(new ChainSeed(type, List.of(nodeId), "Detected " + type.toLowerCase(Locale.ROOT).replace('_', ' ') + " transcript beat."));
                }
            }
        }
        return uniqueSeeds(seeds);
    }

    private List<ChainSeed> interestingSeeds(List<Map<String, Object>> transcript) {
        return transcript.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> node) -> interestingness(node)).reversed())
                .limit(12)
                .map(node -> new ChainSeed("INTERESTINGNESS", List.of(stringValue(node.get("id"), "")), "High-interest transcript beat."))
                .filter(seed -> !seed.nodeIds().isEmpty() && !seed.nodeIds().get(0).isBlank())
                .toList();
    }

    private List<ChainSeed> uniqueSeeds(List<ChainSeed> seeds) {
        List<ChainSeed> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ChainSeed seed : seeds) {
            String key = seed.chainType() + ":" + String.join(",", seed.nodeIds());
            if (seen.add(key)) {
                result.add(seed);
            }
        }
        return result;
    }

    private int planScore(ChainSeed seed, int rank) {
        if (seed.score() > 0) {
            return Math.max(50, Math.min(100, seed.score() - Math.min(8, Math.max(0, rank - 1))));
        }
        return Math.max(70, 98 - rank);
    }

    private int averageInterestingness(List<Map<String, Object>> transcript, List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return 0;
        }
        int total = 0;
        int count = 0;
        for (Map<String, Object> node : transcript) {
            if (nodeIds.contains(stringValue(node.get("id"), ""))) {
                total += interestingness(node);
                count++;
            }
        }
        return count == 0 ? 0 : Math.round((float) total / count);
    }

    private String detectedStandaloneType(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (normalized.matches(".*\\b(punchline|joke|funny|laughed|laugh)\\b.*")) {
            return "PUNCHLINE";
        }
        if (normalized.matches(".*\\b(reveal|secret|truth|finally|turns out|actually)\\b.*")) {
            return "REVEAL";
        }
        return "";
    }

    private String operationFor(String chainType) {
        return switch (stringValue(chainType, "SEQUENTIAL")) {
            case "QUESTION_ANSWER" -> "KEEP_QA_CHAIN";
            case "PROBLEM_SOLUTION" -> "KEEP_PROBLEM_SOLUTION_CHAIN";
            case "CAUSE_EFFECT" -> "KEEP_REVEAL_CHAIN";
            case "PUNCHLINE" -> "KEEP_PUNCHLINE_SETUP";
            case "REVEAL" -> "KEEP_REVEAL";
            case "ESCALATION" -> "KEEP_ESCALATION_CHAIN";
            case "CONTRAST" -> "KEEP_CONTRAST";
            default -> "KEEP_INTERESTING_CHAIN";
        };
    }

    private String hookTypeFor(String chainType, int rank) {
        return switch (stringValue(chainType, "SEQUENTIAL")) {
            case "QUESTION_ANSWER" -> "Question Hook";
            case "PROBLEM_SOLUTION" -> "Problem/Solution";
            case "CAUSE_EFFECT", "REVEAL" -> "Reveal Hook";
            case "PUNCHLINE" -> "Punchline Hook";
            case "CONTRAST" -> "Contrarian Hook";
            default -> rank % 2 == 0 ? "Aha Moment" : "Insight Hook";
        };
    }

    private String chainLabel(String chainType) {
        return stringValue(chainType, "SEQUENTIAL").toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String titleFor(CreatorShortVideo video, ChainSeed seed, int rank) {
        String base = video == null || video.getTitle() == null || video.getTitle().isBlank() ? "Short candidate" : video.getTitle();
        if (rank == 1) {
            return truncate(base, 180);
        }
        return truncate(base + " - " + chainLabel(seed.chainType()) + " " + rank, 180);
    }

    private double duration(List<Map<String, Object>> nodes) {
        double total = 0.0;
        for (Map<String, Object> node : nodes) {
            total += nodeDuration(node);
        }
        return total;
    }

    private double nodeDuration(Map<String, Object> node) {
        double start = doubleValue(node.get("start"), 0.0);
        double end = Math.max(start, doubleValue(node.get("end"), start));
        return Math.max(0.1, end - start);
    }

    private int interestingness(Map<String, Object> node) {
        Object existing = node.get("interestingness");
        if (existing instanceof Number number && number.intValue() > 0) {
            return Math.max(0, Math.min(100, number.intValue()));
        }
        String text = stringValue(node.get("transcript"), "").toLowerCase(Locale.ROOT);
        int score = 45;
        if (text.contains("?")) score += 12;
        if (text.matches(".*\\b(secret|mistake|problem|why|how|result|truth|money|growth|staff|customer|reveal|punchline)\\b.*")) score += 16;
        if (text.length() > 80) score += 8;
        if (text.length() < 15) score -= 8;
        return Math.max(0, Math.min(100, score));
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

    private String truncate(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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

    private record ChainSeed(String chainType, List<String> nodeIds, String reason, int score) {
        private ChainSeed(String chainType, List<String> nodeIds, String reason) {
            this(chainType, nodeIds, reason, 0);
        }
    }

    public record CompressionPlanningResult(
            List<Map<String, Object>> plans,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
