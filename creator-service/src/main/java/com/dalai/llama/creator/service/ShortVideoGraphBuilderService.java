package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ShortVideoGraphBuilderService {

    public GraphBuildResult build(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            List<Map<String, Object>> scenes
    ) {
        List<Map<String, Object>> safeTranscript = copyList(transcript);
        List<Map<String, Object>> safeScenes = copyList(scenes);
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        for (Map<String, Object> scene : safeScenes) {
            nodes.add(sceneNode(scene));
        }
        for (Map<String, Object> segment : safeTranscript) {
            nodes.add(segmentNode(segment));
        }

        for (int index = 1; index < safeScenes.size(); index++) {
            edges.add(edge(
                    stringValue(safeScenes.get(index - 1).get("id"), ""),
                    stringValue(safeScenes.get(index).get("id"), ""),
                    "SCENE_SEQUENCE",
                    "Source scene order."
            ));
        }
        for (Map<String, Object> segment : safeTranscript) {
            String sceneId = stringValue(segment.get("sceneId"), "");
            String nodeId = stringValue(segment.get("id"), "");
            if (!sceneId.isBlank() && !nodeId.isBlank()) {
                edges.add(edge(sceneId, nodeId, "SCENE_CONTAINS", "Transcript segment belongs to this detected source scene."));
            }
        }
        for (int index = 1; index < safeTranscript.size(); index++) {
            Map<String, Object> previous = safeTranscript.get(index - 1);
            Map<String, Object> current = safeTranscript.get(index);
            EdgeDecision decision = edgeDecision(previous, current, videoDna);
            edges.add(edge(
                    stringValue(previous.get("id"), ""),
                    stringValue(current.get("id"), ""),
                    decision.type(),
                    decision.reason()
            ));
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("analysisSource", "full_transcript_scene_graph");
        graph.put("graphViews", List.of("Conversation Graph", "Story Graph", "Scene Graph", "Compression Graph"));
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        graph.put("conversationGraph", conversationGraph(safeTranscript, edges));
        graph.put("storyGraph", storyGraph(safeTranscript, videoDna));
        graph.put("sceneGraph", sceneGraph(safeScenes, safeTranscript));
        graph.put("compressionGraph", compressionGraph(video, safeTranscript, edges));
        graph.put("metadata", metadata(video, safeTranscript, safeScenes, edges));

        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceRow(
                "CONVERSATION_STRUCTURE",
                safeTranscript.isEmpty() ? "WARN" : "COMPLETED",
                safeTranscript.isEmpty()
                        ? "Conversation graph could not be built because transcript nodes are missing."
                        : "Conversation structure was built from full transcript speaker turns and semantic transitions.",
                safeTranscript.isEmpty() ? 0.25 : 0.8,
                Map.of("transcriptNodeCount", safeTranscript.size())
        ));
        trace.add(traceRow(
                "VIDEO_GRAPH_BUILDER",
                safeTranscript.isEmpty() ? "WARN" : "COMPLETED",
                safeTranscript.isEmpty()
                        ? "Real graph builder has no transcript nodes to connect."
                        : "Real graph builder created conversation, story, scene, and compression graph views.",
                safeTranscript.isEmpty() ? 0.25 : 0.84,
                Map.of("nodeCount", nodes.size(), "edgeCount", edges.size(), "sceneCount", safeScenes.size())
        ));

        Map<String, Object> buildMetadata = new LinkedHashMap<>();
        buildMetadata.put("source", "deterministic_graph_builder");
        buildMetadata.put("generatedAt", OffsetDateTime.now().toString());
        buildMetadata.put("nodeCount", nodes.size());
        buildMetadata.put("edgeCount", edges.size());
        buildMetadata.put("transcriptNodeCount", safeTranscript.size());
        buildMetadata.put("sceneCount", safeScenes.size());

        return new GraphBuildResult(graph, trace, buildMetadata);
    }

    private Map<String, Object> sceneNode(Map<String, Object> scene) {
        Map<String, Object> node = new LinkedHashMap<>();
        String id = stringValue(scene.get("id"), "");
        node.put("id", id);
        node.put("label", stringValue(scene.get("label"), id));
        node.put("type", "SCENE");
        node.put("start", scene.get("start"));
        node.put("end", scene.get("end"));
        node.put("frameIds", scene.getOrDefault("frameIds", List.of()));
        node.put("representativeFrameId", scene.getOrDefault("representativeFrameId", ""));
        return node;
    }

    private Map<String, Object> segmentNode(Map<String, Object> segment) {
        String text = stringValue(segment.get("transcript"), "");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", stringValue(segment.get("id"), ""));
        node.put("label", truncate(text, 70));
        node.put("type", nodeType(text));
        node.put("start", segment.get("start"));
        node.put("end", segment.get("end"));
        node.put("speaker", segment.getOrDefault("speaker", "Speaker 1"));
        node.put("sceneId", segment.getOrDefault("sceneId", ""));
        node.put("interestingness", interestingness(segment, text));
        node.put("frames", segment.getOrDefault("frames", List.of()));
        return node;
    }

    private Map<String, Object> conversationGraph(List<Map<String, Object>> transcript, List<Map<String, Object>> edges) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("speakerTurns", transcript.stream().map(node -> stringValue(node.get("speaker"), "Speaker 1")).distinct().count());
        graph.put("nodes", transcript.stream().map(node -> stringValue(node.get("id"), "")).filter(id -> !id.isBlank()).toList());
        graph.put("edges", edges.stream()
                .filter(edge -> Set.of("QUESTION_ANSWER", "PROBLEM_SOLUTION", "CONTRAST", "CAUSE_EFFECT", "SEQUENTIAL").contains(stringValue(edge.get("type"), "")))
                .toList());
        return graph;
    }

    private Map<String, Object> storyGraph(List<Map<String, Object>> transcript, Map<String, Object> videoDna) {
        List<Map<String, Object>> beats = new ArrayList<>();
        int count = transcript.size();
        for (int index = 0; index < count; index++) {
            Map<String, Object> node = transcript.get(index);
            Map<String, Object> beat = new LinkedHashMap<>();
            beat.put("id", "beat-%03d".formatted(index + 1));
            beat.put("nodeId", node.get("id"));
            beat.put("role", beatRole(index, count, stringValue(node.get("transcript"), "")));
            beat.put("start", node.get("start"));
            beat.put("end", node.get("end"));
            beat.put("summary", truncate(stringValue(node.get("transcript"), ""), 110));
            beats.add(beat);
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("structureType", stringValue(videoDna == null ? null : videoDna.get("structureType"), "unknown"));
        graph.put("beats", beats);
        return graph;
    }

    private Map<String, Object> sceneGraph(List<Map<String, Object>> scenes, List<Map<String, Object>> transcript) {
        List<Map<String, Object>> scenePayloads = new ArrayList<>();
        for (Map<String, Object> scene : scenes) {
            String sceneId = stringValue(scene.get("id"), "");
            List<String> nodeIds = transcript.stream()
                    .filter(node -> sceneId.equals(stringValue(node.get("sceneId"), "")))
                    .map(node -> stringValue(node.get("id"), ""))
                    .filter(id -> !id.isBlank())
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>(scene);
            payload.put("transcriptNodeIds", nodeIds);
            scenePayloads.add(payload);
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("scenes", scenePayloads);
        graph.put("sceneCount", scenes.size());
        return graph;
    }

    private Map<String, Object> compressionGraph(CreatorShortVideo video, List<Map<String, Object>> transcript, List<Map<String, Object>> edges) {
        int targetDuration = video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds();
        List<Map<String, Object>> anchors = transcript.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> node) -> interestingness(node, stringValue(node.get("transcript"), ""))).reversed())
                .limit(12)
                .map(node -> {
                    Map<String, Object> anchor = new LinkedHashMap<>();
                    anchor.put("nodeId", node.get("id"));
                    anchor.put("start", node.get("start"));
                    anchor.put("end", node.get("end"));
                    anchor.put("score", interestingness(node, stringValue(node.get("transcript"), "")));
                    anchor.put("reason", compressionReason(stringValue(node.get("transcript"), "")));
                    return anchor;
                })
                .toList();
        List<Map<String, Object>> protectedChains = edges.stream()
                .filter(edge -> Set.of("QUESTION_ANSWER", "PROBLEM_SOLUTION", "CAUSE_EFFECT").contains(stringValue(edge.get("type"), "")))
                .map(edge -> {
                    Map<String, Object> chain = new LinkedHashMap<>();
                    chain.put("from", edge.get("from"));
                    chain.put("to", edge.get("to"));
                    chain.put("type", edge.get("type"));
                    chain.put("reason", "Preserve this semantic relation during short compression.");
                    return chain;
                })
                .toList();
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("targetDurationSeconds", targetDuration);
        graph.put("anchors", anchors);
        graph.put("protectedChains", protectedChains);
        graph.put("strategy", "KEEP_SEMANTIC_CHAINS_FROM_FULL_TRANSCRIPT");
        return graph;
    }

    private EdgeDecision edgeDecision(Map<String, Object> previous, Map<String, Object> current, Map<String, Object> videoDna) {
        String previousText = stringValue(previous.get("transcript"), "").toLowerCase(Locale.ROOT);
        String currentText = stringValue(current.get("transcript"), "").toLowerCase(Locale.ROOT);
        if (previousText.contains("?") || previousText.matches(".*\\b(why|what|how|when|where|who)\\b.*")) {
            return new EdgeDecision("QUESTION_ANSWER", "Question or prompt followed by likely answer/context.");
        }
        if (previousText.matches(".*\\b(problem|issue|mistake|challenge|struggle|pain)\\b.*")
                || currentText.matches(".*\\b(solution|fix|solve|because|therefore|so)\\b.*")) {
            return new EdgeDecision("PROBLEM_SOLUTION", "Problem or friction transitions into solution/context.");
        }
        if (currentText.matches(".*\\b(because|therefore|so|that's why|as a result)\\b.*")) {
            return new EdgeDecision("CAUSE_EFFECT", "Causal relationship detected between adjacent transcript nodes.");
        }
        if (currentText.matches(".*\\b(but|however|instead|actually)\\b.*")) {
            return new EdgeDecision("CONTRAST", "Contrast or reframing transition.");
        }
        String structure = stringValue(videoDna == null ? null : videoDna.get("structureType"), "").toLowerCase(Locale.ROOT);
        if ("dramatic".equals(structure)) {
            return new EdgeDecision("ESCALATION", "Dramatic structure preserves escalation order.");
        }
        return new EdgeDecision("SEQUENTIAL", "Preserve source transcript order.");
    }

    private Map<String, Object> edge(String from, String to, String type, String reason) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", from);
        edge.put("to", to);
        edge.put("type", type);
        edge.put("reason", reason);
        return edge;
    }

    private Map<String, Object> metadata(CreatorShortVideo video, List<Map<String, Object>> transcript, List<Map<String, Object>> scenes, List<Map<String, Object>> edges) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("shortVideoId", video == null || video.getId() == null ? "" : video.getId().toString());
        metadata.put("source", "deterministic_graph_builder");
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("transcriptNodeCount", transcript.size());
        metadata.put("sceneCount", scenes.size());
        metadata.put("edgeCount", edges.size());
        metadata.put("edgeTypes", edges.stream().map(edge -> stringValue(edge.get("type"), "")).filter(type -> !type.isBlank()).collect(Collectors.toSet()));
        return metadata;
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

    private String nodeType(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (normalized.contains("?") || normalized.matches(".*\\b(why|what|how|when|where|who)\\b.*")) {
            return "QUESTION";
        }
        if (normalized.matches(".*\\b(solution|fix|solve|answer)\\b.*")) {
            return "SOLUTION";
        }
        if (normalized.matches(".*\\b(problem|mistake|challenge|issue)\\b.*")) {
            return "PROBLEM";
        }
        if (normalized.matches(".*\\b(result|finally|therefore|so)\\b.*")) {
            return "PAYOFF";
        }
        return "SEGMENT";
    }

    private String beatRole(int index, int count, String text) {
        String type = nodeType(text);
        if ("QUESTION".equals(type)) return "hook_question";
        if ("PROBLEM".equals(type)) return "tension";
        if ("SOLUTION".equals(type)) return "answer";
        if ("PAYOFF".equals(type)) return "payoff";
        if (count <= 1 || index == 0) return "setup";
        if (index >= Math.max(0, count - 2)) return "payoff";
        return "development";
    }

    private String compressionReason(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (normalized.contains("?")) return "Question hook candidate.";
        if (normalized.matches(".*\\b(problem|mistake|challenge|issue)\\b.*")) return "Conflict or problem setup.";
        if (normalized.matches(".*\\b(solution|fix|solve|result|therefore)\\b.*")) return "Solution or payoff candidate.";
        return "High continuity/interestingness transcript segment.";
    }

    private int interestingness(Map<String, Object> node, String text) {
        Object existing = node.get("interestingness");
        if (existing instanceof Number number && number.intValue() > 0) {
            return Math.max(0, Math.min(100, number.intValue()));
        }
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int score = 45;
        if (normalized.contains("?")) score += 12;
        if (normalized.matches(".*\\b(secret|mistake|problem|why|how|result|truth|money|growth|staff|customer)\\b.*")) score += 16;
        if (normalized.length() > 80) score += 8;
        if (normalized.length() < 15) score -= 8;
        return Math.max(0, Math.min(100, score));
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

    private String truncate(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private record EdgeDecision(String type, String reason) {
    }

    public record GraphBuildResult(
            Map<String, Object> graph,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
