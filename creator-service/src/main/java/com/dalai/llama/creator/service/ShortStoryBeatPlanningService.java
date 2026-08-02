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
public class ShortStoryBeatPlanningService {

    private static final String SOURCE = "deterministic_story_beat_planning_worker";
    private static final Set<String> PROTECTED_CHAIN_TYPES = Set.of(
            "QUESTION_ANSWER",
            "PROBLEM_SOLUTION",
            "CAUSE_EFFECT",
            "SETUP_PAYOFF",
            "ESCALATION",
            "CONTRAST",
            "REVEAL",
            "PUNCHLINE"
    );

    public StoryBeatPlanningResult plan(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> scenes,
            Map<String, Object> storyUnderstanding,
            Map<String, Object> storyCritic,
            ShortInterestingnessScoringService.InterestingnessScoringResult interestingnessScoring,
            ShortInterestingnessCriticService.InterestingnessCriticResult interestingnessCritic,
            List<Map<String, Object>> plannerCandidates
    ) {
        List<Map<String, Object>> nodes = copyList(transcript);
        nodes.sort(Comparator.comparingDouble(node -> doubleValue(node.get("start"), 0.0)));
        ensureNodeIds(nodes);

        int requested = Math.max(1, Math.min(video == null || video.getRequestedShorts() == null ? 20 : video.getRequestedShorts(), 50));
        int targetDuration = Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());
        if (nodes.isEmpty()) {
            List<Map<String, Object>> candidates = copyList(plannerCandidates);
            Map<String, Object> metadata = baseMetadata(requested, targetDuration, 0, 0, 0, false, false);
            metadata.put("status", "WARN");
            metadata.put("reason", "No transcript nodes were available to build story-beat shorts.");
            return new StoryBeatPlanningResult(
                    candidates,
                    List.of(),
                    List.of(),
                    List.of(traceRow("STORY_BEAT_PLANNING", "WARN", "Story beat planning skipped because transcript nodes are missing.", 0.22, metadata)),
                    metadata
            );
        }

        List<Map<String, Object>> safeScenes = copyList(scenes);
        List<Map<String, Object>> safePlannerCandidates = copyList(plannerCandidates);
        Map<String, Map<String, Object>> nodesById = indexById(nodes);
        Map<String, Map<String, Object>> scenesById = indexById(safeScenes);
        List<Map<String, Object>> edges = listOfMaps(graph == null ? null : graph.get("edges"));

        List<StorySeed> seeds = storySeeds(
                video,
                nodes,
                edges,
                interestingnessScoring,
                interestingnessCritic,
                storyUnderstanding,
                storyCritic
        );
        if (seeds.isEmpty()) {
            seeds.addAll(fallbackSeeds(nodes, requested));
        }

        List<Map<String, Object>> generatedCandidates = new ArrayList<>();
        List<Map<String, Object>> plans = new ArrayList<>();
        List<Map<String, Object>> intents = new ArrayList<>();
        Set<String> seenEdlKeys = new HashSet<>();

        int rank = 1;
        for (StorySeed seed : seeds) {
            if (rank > requested) {
                break;
            }
            List<Map<String, Object>> selected = selectStoryNodes(nodes, nodesById, edges, seed, targetDuration);
            if (selected.isEmpty()) {
                continue;
            }
            Map<String, Object> intent = intentFor(video, videoDna, seed, selected, storyUnderstanding, storyCritic, rank);
            Map<String, Object> beatPlan = beatPlanFor(intent, selected, safeScenes, scenesById, edges, seed);
            Map<String, Object> edl = editDecisionList(video, selected, seed, intent, beatPlan, targetDuration, rank);
            String edlKey = edlKey(edl);
            if (!seenEdlKeys.add(edlKey)) {
                continue;
            }

            Map<String, Object> plan = compressionPlan(video, seed, intent, beatPlan, edl, rank);
            Map<String, Object> candidate = candidateFromPlan(video, plan, intent, beatPlan);
            plans.add(plan);
            generatedCandidates.add(candidate);
            intents.add(intent);
            rank++;
        }

        List<Map<String, Object>> candidates = mergeCandidates(generatedCandidates, safePlannerCandidates, requested);
        String status = generatedCandidates.isEmpty() ? "WARN" : generatedCandidates.size() < requested ? "COMPLETED_WITH_FALLBACKS" : "COMPLETED";
        Map<String, Object> metadata = baseMetadata(
                requested,
                targetDuration,
                generatedCandidates.size(),
                plans.size(),
                intents.size(),
                graphBacked(graph, edges),
                sceneBacked(safeScenes, generatedCandidates)
        );
        metadata.put("status", status);
        metadata.put("seedCount", seeds.size());
        metadata.put("plannerCandidateCarryoverCount", Math.max(0, candidates.size() - generatedCandidates.size()));
        metadata.put("acceptedWindowCount", acceptedWindowIds(interestingnessCritic).size());
        metadata.put("intents", intents);
        metadata.put("planSummaries", planSummaries(plans));
        metadata.put("videoType", stringValue(videoDna == null ? null : videoDna.get("primaryType"), "unknown"));
        metadata.put("graphSource", stringValue(graph == null ? null : graph.get("analysisSource"), "unknown"));

        String summary = generatedCandidates.isEmpty()
                ? "Story beat planner could not create graph-backed story intents, so downstream workers will use existing candidates."
                : "Created story intents, beat hooks, and graph-backed cut seeds from transcript, scenes, graph, and interestingness evidence.";
        List<Map<String, Object>> trace = List.of(traceRow(
                "STORY_BEAT_PLANNING",
                status,
                summary,
                generatedCandidates.isEmpty() ? 0.34 : generatedCandidates.size() >= requested ? 0.9 : 0.78,
                metadata
        ));
        return new StoryBeatPlanningResult(candidates, plans, intents, trace, metadata);
    }

    private List<StorySeed> storySeeds(
            CreatorShortVideo video,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            ShortInterestingnessScoringService.InterestingnessScoringResult interestingnessScoring,
            ShortInterestingnessCriticService.InterestingnessCriticResult interestingnessCritic,
            Map<String, Object> storyUnderstanding,
            Map<String, Object> storyCritic
    ) {
        Map<String, Map<String, Object>> nodesById = indexById(nodes);
        List<StorySeed> seeds = new ArrayList<>();
        Set<String> acceptedWindowIds = new HashSet<>(acceptedWindowIds(interestingnessCritic));
        boolean filterAccepted = !acceptedWindowIds.isEmpty() && interestingnessCritic != null && !"FAIL".equalsIgnoreCase(interestingnessCritic.status());

        for (Map<String, Object> window : copyList(interestingnessScoring == null ? null : interestingnessScoring.candidateWindows())) {
            String windowId = stringValue(window.get("windowId"), "");
            if (filterAccepted && !acceptedWindowIds.contains(windowId)) {
                continue;
            }
            List<String> nodeIds = listOfStrings(window.get("nodeIds")).stream()
                    .filter(nodesById::containsKey)
                    .toList();
            if (nodeIds.isEmpty()) {
                continue;
            }
            String chainType = stringValue(window.get("chainType"), "INTERESTINGNESS");
            seeds.add(new StorySeed(
                    chainType,
                    nodeIds,
                    stringValue(window.get("anchorNodeId"), nodeIds.get(0)),
                    intValue(window.get("score"), averageInterestingness(nodes, nodeIds)),
                    stringValue(window.get("reason"), "Ranked candidate window accepted by interestingness analysis."),
                    stringValue(window.get("windowId"), ""),
                    "interestingness_window"
            ));
        }

        for (Map<String, Object> edge : edges) {
            String type = stringValue(edge.get("type"), "");
            if (!PROTECTED_CHAIN_TYPES.contains(type)) {
                continue;
            }
            String from = stringValue(edge.get("from"), "");
            String to = stringValue(edge.get("to"), "");
            if (from.isBlank() || to.isBlank() || from.startsWith("scene-") || to.startsWith("scene-")) {
                continue;
            }
            if (nodesById.containsKey(from) && nodesById.containsKey(to)) {
                seeds.add(new StorySeed(
                        type,
                        List.of(from, to),
                        from,
                        Math.max(62, averageInterestingness(nodes, List.of(from, to))),
                        stringValue(edge.get("reason"), "Protected graph edge should be cut as one story beat."),
                        "",
                        "graph_protected_chain"
                ));
            }
        }

        List<Map<String, Object>> storyBeats = listOfMaps(mapValue(storyUnderstanding == null ? null : storyUnderstanding.get("storyGraph")).get("beats"));
        if (storyBeats.isEmpty()) {
            storyBeats = listOfMaps(mapValue(storyUnderstanding == null ? null : storyUnderstanding.get("narrativeArc")).get("beats"));
        }
        for (Map<String, Object> beat : storyBeats) {
            String nodeId = stringValue(firstNonBlank(beat.get("nodeId"), beat.get("id")), "");
            if (!nodeId.isBlank() && nodesById.containsKey(nodeId)) {
                String role = stringValue(beat.get("role"), "STORY_BEAT").toUpperCase(Locale.ROOT);
                seeds.add(new StorySeed(
                        role.contains("PAYOFF") || role.contains("REVEAL") ? "SETUP_PAYOFF" : "STORY_BEAT",
                        List.of(nodeId),
                        nodeId,
                        Math.max(58, interestingness(nodesById.get(nodeId))),
                        stringValue(beat.get("summary"), "Story-understanding beat."),
                        "",
                        "story_understanding"
                ));
            }
        }

        for (String nodeId : storyRiskNodeIds(storyCritic)) {
            if (nodesById.containsKey(nodeId)) {
                seeds.add(new StorySeed(
                        "CONTEXT_REPAIR",
                        List.of(nodeId),
                        nodeId,
                        Math.max(55, interestingness(nodesById.get(nodeId)) - 4),
                        "Story critic marked this node as needing context; build a safe chain around it.",
                        "",
                        "story_critic_context"
                ));
            }
        }

        seeds.addAll(topMomentSeeds(nodes, interestingnessScoring));
        return uniqueSeeds(seeds, video == null || video.getRequestedShorts() == null ? 20 : video.getRequestedShorts());
    }

    private List<StorySeed> fallbackSeeds(List<Map<String, Object>> nodes, int requested) {
        return nodes.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> node) -> interestingness(node)).reversed())
                .limit(Math.max(1, requested))
                .map(node -> {
                    String nodeId = stringValue(node.get("id"), "");
                    return new StorySeed(
                            detectedChainType(stringValue(node.get("transcript"), "")),
                            List.of(nodeId),
                            nodeId,
                            interestingness(node),
                            "Fallback high-interest transcript beat.",
                            "",
                            "fallback_top_moment"
                    );
                })
                .filter(seed -> !seed.nodeIds().isEmpty() && !seed.nodeIds().get(0).isBlank())
                .toList();
    }

    private List<StorySeed> topMomentSeeds(
            List<Map<String, Object>> nodes,
            ShortInterestingnessScoringService.InterestingnessScoringResult scoring
    ) {
        Map<String, Map<String, Object>> nodesById = indexById(nodes);
        List<StorySeed> seeds = new ArrayList<>();
        for (Map<String, Object> moment : copyList(scoring == null ? null : scoring.topMoments())) {
            String nodeId = stringValue(moment.get("nodeId"), "");
            if (nodeId.isBlank() || !nodesById.containsKey(nodeId)) {
                continue;
            }
            seeds.add(new StorySeed(
                    detectedChainType(stringValue(nodesById.get(nodeId).get("transcript"), "")),
                    List.of(nodeId),
                    nodeId,
                    intValue(moment.get("score"), interestingness(nodesById.get(nodeId))),
                    "High-scoring top moment from robust interestingness worker.",
                    "",
                    "top_moment"
            ));
        }
        return seeds;
    }

    private List<Map<String, Object>> selectStoryNodes(
            List<Map<String, Object>> orderedNodes,
            Map<String, Map<String, Object>> nodesById,
            List<Map<String, Object>> edges,
            StorySeed seed,
            int targetDuration
    ) {
        List<Map<String, Object>> selected = new ArrayList<>();
        for (String nodeId : seed.nodeIds()) {
            addNode(selected, nodesById.get(nodeId));
        }

        for (Map<String, Object> edge : edges) {
            String type = stringValue(edge.get("type"), "");
            if (!PROTECTED_CHAIN_TYPES.contains(type) && !"SEQUENTIAL".equals(type)) {
                continue;
            }
            String from = stringValue(edge.get("from"), "");
            String to = stringValue(edge.get("to"), "");
            if (containsNode(selected, from) && nodesById.containsKey(to) && duration(selected) < targetDuration * 0.9) {
                addNode(selected, nodesById.get(to));
            }
            if (containsNode(selected, to) && nodesById.containsKey(from) && duration(selected) < targetDuration * 0.72) {
                addNode(selected, nodesById.get(from));
            }
        }

        selected.sort(Comparator.comparingDouble(node -> doubleValue(node.get("start"), 0.0)));
        if (!selected.isEmpty()) {
            int firstIndex = orderedNodes.indexOf(selected.get(0));
            int lastIndex = orderedNodes.indexOf(selected.get(selected.size() - 1));
            double currentDuration = duration(selected);
            if (firstIndex > 0 && currentDuration < targetDuration * 0.55) {
                Map<String, Object> previous = orderedNodes.get(firstIndex - 1);
                if (fits(currentDuration, previous, targetDuration)) {
                    addNode(selected, previous);
                    currentDuration = duration(selected);
                }
            }
            if (lastIndex >= 0 && lastIndex < orderedNodes.size() - 1 && currentDuration < targetDuration * 0.86) {
                Map<String, Object> next = orderedNodes.get(lastIndex + 1);
                if (fits(currentDuration, next, targetDuration)) {
                    addNode(selected, next);
                }
            }
        }

        selected.sort(Comparator.comparingDouble(node -> doubleValue(node.get("start"), 0.0)));
        return trimToTarget(selected, seed, targetDuration);
    }

    private Map<String, Object> intentFor(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            StorySeed seed,
            List<Map<String, Object>> selected,
            Map<String, Object> storyUnderstanding,
            Map<String, Object> storyCritic,
            int rank
    ) {
        String combinedText = selected.stream()
                .map(node -> stringValue(node.get("transcript"), ""))
                .reduce("", (left, right) -> (left + " " + right).trim());
        String chainType = stringValue(seed.chainType(), detectedChainType(combinedText));
        String intentType = intentType(chainType, combinedText, rank);
        String viewerIntent = viewerIntent(intentType, combinedText);
        String promise = storyPromise(intentType, selected, video, storyUnderstanding);
        String hookType = hookTypeFor(intentType, chainType, combinedText, rank);
        String openingLine = openingLineFor(intentType, firstText(selected), lastText(selected), promise);
        String onScreenHook = onScreenHook(openingLine, promise);
        List<String> nodeIds = selected.stream().map(node -> stringValue(node.get("id"), "")).filter(id -> !id.isBlank()).toList();
        List<String> sceneIds = selected.stream().map(node -> stringValue(node.get("sceneId"), "")).filter(id -> !id.isBlank()).distinct().toList();

        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("intentId", "story-intent-%03d".formatted(rank));
        intent.put("rankIndex", rank);
        intent.put("source", SOURCE);
        intent.put("intentType", intentType);
        intent.put("viewerIntent", viewerIntent);
        intent.put("storyPromise", promise);
        intent.put("chainType", chainType);
        intent.put("hookType", hookType);
        intent.put("openingLine", openingLine);
        intent.put("onScreenHook", onScreenHook);
        intent.put("score", Math.max(50, Math.min(100, seed.score())));
        intent.put("sourceNodeIds", nodeIds);
        intent.put("sceneIds", sceneIds);
        intent.put("sourceStart", firstNumber(selected, "start"));
        intent.put("sourceEnd", lastNumber(selected, "end"));
        intent.put("selectionReason", seed.reason());
        intent.put("seedSource", seed.source());
        intent.put("windowId", seed.windowId());
        intent.put("videoType", stringValue(videoDna == null ? null : videoDna.get("primaryType"), "unknown"));
        intent.put("riskFlags", riskFlags(selected, storyCritic));
        return intent;
    }

    private Map<String, Object> beatPlanFor(
            Map<String, Object> intent,
            List<Map<String, Object>> selected,
            List<Map<String, Object>> scenes,
            Map<String, Map<String, Object>> scenesById,
            List<Map<String, Object>> edges,
            StorySeed seed
    ) {
        List<Map<String, Object>> storyBeats = new ArrayList<>();
        int count = selected.size();
        for (int index = 0; index < count; index++) {
            Map<String, Object> node = selected.get(index);
            String role = beatRole(index, count, seed.chainType(), stringValue(node.get("transcript"), ""));
            Map<String, Object> beat = new LinkedHashMap<>();
            beat.put("beatId", "beat-%03d".formatted(index + 1));
            beat.put("role", role);
            beat.put("nodeId", stringValue(node.get("id"), ""));
            beat.put("sceneId", stringValue(node.get("sceneId"), ""));
            beat.put("sourceStart", round3(doubleValue(node.get("start"), 0.0)));
            beat.put("sourceEnd", round3(doubleValue(node.get("end"), doubleValue(node.get("start"), 0.0))));
            beat.put("text", truncate(stringValue(node.get("transcript"), ""), 260));
            beat.put("frames", node.getOrDefault("frames", List.of()));
            beat.put("reason", beatReason(role, seed.chainType()));
            storyBeats.add(beat);
        }

        List<Map<String, Object>> sceneEvidence = sceneEvidence(listOfStrings(intent.get("sceneIds")), scenesById, scenes);
        Map<String, Object> hook = new LinkedHashMap<>();
        hook.put("source", SOURCE);
        hook.put("hookType", intent.get("hookType"));
        hook.put("openingLine", intent.get("openingLine"));
        hook.put("onScreenHook", intent.get("onScreenHook"));
        hook.put("storyPromise", intent.get("storyPromise"));
        hook.put("firstBeatNodeId", storyBeats.isEmpty() ? "" : storyBeats.get(0).get("nodeId"));
        hook.put("payoffBeatNodeId", storyBeats.isEmpty() ? "" : storyBeats.get(storyBeats.size() - 1).get("nodeId"));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("source", SOURCE);
        plan.put("intentId", intent.get("intentId"));
        plan.put("intentType", intent.get("intentType"));
        plan.put("hook", hook);
        plan.put("storyBeats", storyBeats);
        plan.put("sceneEvidence", sceneEvidence);
        plan.put("graphChains", graphChainsFor(selected, edges));
        plan.put("cutPolicy", "cut_source_segments_in_story_order_from_graph_evidence");
        plan.put("protectedNodeIds", seed.nodeIds());
        plan.put("selectionReason", seed.reason());
        return plan;
    }

    private Map<String, Object> editDecisionList(
            CreatorShortVideo video,
            List<Map<String, Object>> selected,
            StorySeed seed,
            Map<String, Object> intent,
            Map<String, Object> beatPlan,
            int targetDuration,
            int rank
    ) {
        List<Map<String, Object>> storyBeats = listOfMaps(beatPlan.get("storyBeats"));
        Set<String> protectedNodeIds = new HashSet<>(seed.nodeIds());
        List<Map<String, Object>> segments = new ArrayList<>();
        double cursor = 0.0;
        for (int index = 0; index < selected.size(); index++) {
            Map<String, Object> node = selected.get(index);
            double sourceStart = doubleValue(node.get("start"), 0.0);
            double sourceEnd = Math.max(sourceStart + 0.1, doubleValue(node.get("end"), sourceStart + 0.1));
            double remaining = Math.max(0.0, targetDuration - cursor);
            boolean protectedNode = protectedNodeIds.contains(stringValue(node.get("id"), ""));
            if (remaining <= 0.0 && !protectedNode) {
                continue;
            }
            if (sourceEnd - sourceStart > remaining && remaining >= 1.5) {
                sourceEnd = sourceStart + remaining;
            } else if (sourceEnd - sourceStart > remaining && !protectedNode) {
                continue;
            }
            double duration = Math.max(0.1, sourceEnd - sourceStart);
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("nodeId", stringValue(node.get("id"), ""));
            segment.put("sceneId", stringValue(node.get("sceneId"), ""));
            segment.put("sourceStart", round3(sourceStart));
            segment.put("sourceEnd", round3(sourceEnd));
            segment.put("timelineStart", round3(cursor));
            segment.put("timelineEnd", round3(cursor + duration));
            segment.put("durationSeconds", round3(duration));
            segment.put("operation", protectedNode ? operationFor(seed.chainType()) : "KEEP_STORY_CONTEXT");
            segment.put("chainType", seed.chainType());
            segment.put("storyBeatId", index < storyBeats.size() ? storyBeats.get(index).get("beatId") : "beat-%03d".formatted(index + 1));
            segment.put("beatRole", index < storyBeats.size() ? storyBeats.get(index).get("role") : beatRole(index, selected.size(), seed.chainType(), stringValue(node.get("transcript"), "")));
            segment.put("intentId", intent.get("intentId"));
            segment.put("reason", protectedNode ? seed.reason() : "Story context retained to make the beat understandable.");
            segment.put("transcript", stringValue(node.get("transcript"), ""));
            segment.put("frames", node.getOrDefault("frames", List.of()));
            segments.add(segment);
            cursor += duration;
        }

        List<Map<String, Object>> protectedChains = new ArrayList<>();
        Map<String, Object> protectedChain = new LinkedHashMap<>();
        protectedChain.put("type", seed.chainType());
        protectedChain.put("nodeIds", seed.nodeIds());
        protectedChain.put("reason", seed.reason());
        protectedChain.put("intentId", intent.get("intentId"));
        protectedChains.add(protectedChain);

        Map<String, Object> edl = new LinkedHashMap<>();
        edl.put("version", 3);
        edl.put("source", SOURCE);
        edl.put("exactTimestampEdl", true);
        edl.put("strategy", operationFor(seed.chainType()));
        edl.put("rankIndex", rank);
        edl.put("candidateKey", "story-beat-%03d".formatted(rank));
        edl.put("intentId", intent.get("intentId"));
        edl.put("intentType", intent.get("intentType"));
        edl.put("targetDurationSeconds", targetDuration);
        edl.put("actualDurationSeconds", round3(cursor));
        edl.put("timelineStart", 0);
        edl.put("timelineEnd", round3(cursor));
        edl.put("segments", segments);
        edl.put("storyBeats", storyBeats);
        edl.put("sceneIds", listOfStrings(intent.get("sceneIds")));
        edl.put("protectedChains", protectedChains);
        edl.put("selectionReason", seed.reason());
        edl.put("renderPolicy", "concat_source_segments_in_timeline_order");
        edl.put("graphCutPolicy", "preserve_story_beat_nodes_and_scene_context");
        edl.put("platform", video == null ? "youtube_shorts" : stringValue(video.getPlatform(), "youtube_shorts"));
        edl.put("generatedAt", OffsetDateTime.now().toString());
        return edl;
    }

    private Map<String, Object> compressionPlan(
            CreatorShortVideo video,
            StorySeed seed,
            Map<String, Object> intent,
            Map<String, Object> beatPlan,
            Map<String, Object> edl,
            int rank
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("compressionSource", SOURCE);
        metadata.put("storyBeatPlanningSource", SOURCE);
        metadata.put("storyIntent", intent);
        metadata.put("storyBeatPlan", beatPlan);
        metadata.put("interestingnessScore", seed.score());
        metadata.put("selectionReason", seed.reason());
        metadata.put("graphCutSceneIds", intent.getOrDefault("sceneIds", List.of()));
        metadata.put("windowId", seed.windowId());
        metadata.put("seedSource", seed.source());

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("rankIndex", rank);
        plan.put("candidateKey", "story-beat-%03d".formatted(rank));
        plan.put("hookType", intent.get("hookType"));
        plan.put("chainType", seed.chainType());
        plan.put("intentType", intent.get("intentType"));
        plan.put("score", Math.max(55, Math.min(100, seed.score())));
        plan.put("title", titleFor(video, intent, rank));
        plan.put("durationSeconds", Math.max(1, (int) Math.round(doubleValue(edl.get("actualDurationSeconds"), targetDuration(video)))));
        plan.put("editDecisionList", edl);
        plan.put("storyIntent", intent);
        plan.put("storyBeatPlan", beatPlan);
        plan.put("metadata", metadata);
        return plan;
    }

    private Map<String, Object> candidateFromPlan(
            CreatorShortVideo video,
            Map<String, Object> plan,
            Map<String, Object> intent,
            Map<String, Object> beatPlan
    ) {
        Map<String, Object> edl = mapValue(plan.get("editDecisionList"));
        Map<String, Object> metadata = mapValue(plan.get("metadata"));
        metadata.put("source", SOURCE);
        metadata.put("chain", chainLabel(stringValue(plan.get("chainType"), "")));
        metadata.put("storyIntent", intent);
        metadata.put("storyBeatPlan", beatPlan);
        metadata.put("storyBeatHook", mapValue(beatPlan.get("hook")));
        metadata.put("storyBeatSource", SOURCE);
        metadata.put("selectionReason", intent.getOrDefault("selectionReason", ""));
        metadata.put("graphCutSceneIds", intent.getOrDefault("sceneIds", List.of()));
        metadata.put("requiresGraphCut", true);

        Map<String, Object> renderManifest = new LinkedHashMap<>();
        renderManifest.put("renderStatus", "PENDING_REVIEW");
        renderManifest.put("aspectRatio", "9:16");
        renderManifest.put("safeZones", List.of("top_caption_safe", "bottom_ui_safe"));
        renderManifest.put("storyIntentId", intent.get("intentId"));
        renderManifest.put("storyBeatPlan", beatPlan);
        renderManifest.put("cutPolicy", "graph_story_beat_edl");
        renderManifest.put("hookOpeningLine", intent.get("openingLine"));
        renderManifest.put("hookOnScreenText", intent.get("onScreenHook"));

        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("title", plan.get("title"));
        candidate.put("durationSeconds", plan.get("durationSeconds"));
        candidate.put("score", plan.get("score"));
        candidate.put("hookType", plan.get("hookType"));
        candidate.put("editDecisionList", edl);
        candidate.put("captionPlan", captionPlan(video, edl, intent));
        candidate.put("renderManifest", renderManifest);
        candidate.put("metadata", metadata);
        return candidate;
    }

    private Map<String, Object> captionPlan(CreatorShortVideo video, Map<String, Object> edl, Map<String, Object> intent) {
        List<Map<String, Object>> captions = new ArrayList<>();
        captions.add(caption(0, Math.min(2.8, doubleValue(edl.get("actualDurationSeconds"), targetDuration(video))), stringValue(intent.get("onScreenHook"), "")));
        for (Map<String, Object> segment : listOfMaps(edl.get("segments"))) {
            String text = truncate(stringValue(segment.get("transcript"), ""), 96);
            if (text.isBlank()) {
                continue;
            }
            captions.add(caption(
                    doubleValue(segment.get("timelineStart"), 0.0),
                    doubleValue(segment.get("timelineEnd"), doubleValue(segment.get("timelineStart"), 0.0) + 2.0),
                    text
            ));
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("source", SOURCE);
        plan.put("style", "platform-aware");
        plan.put("platform", video == null ? "youtube_shorts" : stringValue(video.getPlatform(), "youtube_shorts"));
        plan.put("density", "medium");
        plan.put("captions", captions.stream().limit(12).toList());
        return plan;
    }

    private List<Map<String, Object>> mergeCandidates(
            List<Map<String, Object>> generated,
            List<Map<String, Object>> carryover,
            int requested
    ) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> candidate : generated) {
            if (merged.size() >= requested) {
                break;
            }
            if (seen.add(candidateKey(candidate))) {
                merged.add(candidate);
            }
        }
        for (Map<String, Object> candidate : carryover) {
            if (merged.size() >= requested) {
                break;
            }
            if (seen.add(candidateKey(candidate))) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private List<StorySeed> uniqueSeeds(List<StorySeed> seeds, int requested) {
        List<StorySeed> sorted = seeds.stream()
                .filter(seed -> seed != null && !seed.nodeIds().isEmpty())
                .sorted(Comparator.comparingInt(StorySeed::score).reversed())
                .toList();
        List<StorySeed> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int limit = Math.max(12, requested * 3);
        for (StorySeed seed : sorted) {
            String key = seed.chainType() + ":" + String.join(",", seed.nodeIds());
            if (seen.add(key)) {
                result.add(seed);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private List<Map<String, Object>> trimToTarget(List<Map<String, Object>> selected, StorySeed seed, int targetDuration) {
        if (duration(selected) <= targetDuration + 0.75) {
            return selected;
        }
        Set<String> protectedNodeIds = new HashSet<>(seed.nodeIds());
        List<Map<String, Object>> result = new ArrayList<>();
        double total = 0.0;
        for (Map<String, Object> node : selected) {
            double nodeDuration = nodeDuration(node);
            boolean protectedNode = protectedNodeIds.contains(stringValue(node.get("id"), ""));
            if (total + nodeDuration <= targetDuration + 0.75 || protectedNode) {
                result.add(node);
                total += nodeDuration;
            }
        }
        if (result.isEmpty() && !selected.isEmpty()) {
            result.add(selected.get(0));
        }
        result.sort(Comparator.comparingDouble(node -> doubleValue(node.get("start"), 0.0)));
        return result;
    }

    private List<Map<String, Object>> sceneEvidence(
            List<String> sceneIds,
            Map<String, Map<String, Object>> scenesById,
            List<Map<String, Object>> scenes
    ) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (String sceneId : sceneIds) {
            Map<String, Object> scene = scenesById.get(sceneId);
            if (scene == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sceneId", sceneId);
            item.put("label", stringValue(scene.get("label"), sceneId));
            item.put("start", scene.getOrDefault("start", 0));
            item.put("end", scene.getOrDefault("end", 0));
            item.put("representativeFrameId", scene.getOrDefault("representativeFrameId", ""));
            item.put("frameIds", scene.getOrDefault("frameIds", List.of()));
            evidence.add(item);
        }
        if (evidence.isEmpty() && !scenes.isEmpty()) {
            Map<String, Object> scene = scenes.get(0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sceneId", stringValue(scene.get("id"), "scene-001"));
            item.put("label", stringValue(scene.get("label"), "Scene 1"));
            item.put("start", scene.getOrDefault("start", 0));
            item.put("end", scene.getOrDefault("end", 0));
            item.put("representativeFrameId", scene.getOrDefault("representativeFrameId", ""));
            item.put("frameIds", scene.getOrDefault("frameIds", List.of()));
            evidence.add(item);
        }
        return evidence;
    }

    private List<Map<String, Object>> graphChainsFor(List<Map<String, Object>> selected, List<Map<String, Object>> edges) {
        Set<String> selectedNodeIds = new HashSet<>(selected.stream().map(node -> stringValue(node.get("id"), "")).toList());
        List<Map<String, Object>> chains = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            String from = stringValue(edge.get("from"), "");
            String to = stringValue(edge.get("to"), "");
            String type = stringValue(edge.get("type"), "");
            if (selectedNodeIds.contains(from) && selectedNodeIds.contains(to) && !from.startsWith("scene-") && !to.startsWith("scene-")) {
                Map<String, Object> chain = new LinkedHashMap<>();
                chain.put("type", type);
                chain.put("from", from);
                chain.put("to", to);
                chain.put("reason", stringValue(edge.get("reason"), "Selected graph relationship."));
                chain.put("protected", PROTECTED_CHAIN_TYPES.contains(type));
                chains.add(chain);
            }
        }
        return chains;
    }

    private Map<String, Object> baseMetadata(
            int requested,
            int targetDuration,
            int candidateCount,
            int planCount,
            int intentCount,
            boolean graphBacked,
            boolean sceneBacked
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", SOURCE);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("requestedShorts", requested);
        metadata.put("targetDurationSeconds", targetDuration);
        metadata.put("candidateCount", candidateCount);
        metadata.put("planCount", planCount);
        metadata.put("intentCount", intentCount);
        metadata.put("graphBacked", graphBacked);
        metadata.put("sceneBacked", sceneBacked);
        metadata.put("contract", Map.of(
                "createsStoryIntents", true,
                "createsStoryBeats", true,
                "createsHookPromise", true,
                "createsCompressionSeeds", true,
                "createsExactTimestampEdl", true
        ));
        return metadata;
    }

    private List<Map<String, Object>> planSummaries(List<Map<String, Object>> plans) {
        return plans.stream().map(plan -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("rankIndex", plan.get("rankIndex"));
            summary.put("candidateKey", plan.get("candidateKey"));
            summary.put("title", plan.get("title"));
            summary.put("hookType", plan.get("hookType"));
            summary.put("intentType", plan.get("intentType"));
            summary.put("score", plan.get("score"));
            summary.put("durationSeconds", plan.get("durationSeconds"));
            Map<String, Object> edl = mapValue(plan.get("editDecisionList"));
            summary.put("segmentCount", listOfMaps(edl.get("segments")).size());
            summary.put("sceneIds", edl.getOrDefault("sceneIds", List.of()));
            return summary;
        }).toList();
    }

    private boolean graphBacked(Map<String, Object> graph, List<Map<String, Object>> edges) {
        return graph != null && !graph.isEmpty() && !edges.isEmpty();
    }

    private boolean sceneBacked(List<Map<String, Object>> scenes, List<Map<String, Object>> candidates) {
        if (scenes.isEmpty() || candidates.isEmpty()) {
            return false;
        }
        for (Map<String, Object> candidate : candidates) {
            Map<String, Object> edl = mapValue(candidate.get("editDecisionList"));
            if (!listOfStrings(edl.get("sceneIds")).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private String intentType(String chainType, String text, int rank) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return switch (stringValue(chainType, "INTERESTINGNESS")) {
            case "QUESTION_ANSWER" -> "ANSWER_THE_QUESTION";
            case "PROBLEM_SOLUTION" -> "SOLVE_THE_PROBLEM";
            case "CAUSE_EFFECT", "REVEAL", "SETUP_PAYOFF" -> "LAND_THE_REVEAL";
            case "CONTRAST" -> "REFRAME_ASSUMPTION";
            case "ESCALATION" -> "BUILD_TO_PAYOFF";
            case "PUNCHLINE" -> "SETUP_PUNCHLINE";
            default -> {
                if (normalized.matches(".*\\b(mistake|problem|wrong|failed|challenge|struggle|staff|customer|money)\\b.*")) {
                    yield "AVOID_THE_MISTAKE";
                }
                if (normalized.matches(".*\\b(why|how|what|when|where|who)\\b.*") || normalized.contains("?")) {
                    yield "EXPLAIN_THE_WHY";
                }
                if (normalized.matches(".*\\b(strange|weird|mystery|hidden|secret|surprising|surprise|impossible|ancient|resilient|unexpected|what if)\\b.*")) {
                    yield "OPEN_CURIOSITY_LOOP";
                }
                if (normalized.matches(".*\\b(idea|concept|meaning|pattern|system|biology|evolution|abstract|principle)\\b.*")) {
                    yield "ABSTRACT_INSIGHT";
                }
                if (normalized.matches(".*\\b(result|truth|actually|finally|turns out|secret|reveal)\\b.*")) {
                    yield "LAND_THE_REVEAL";
                }
                yield rank % 3 == 0 ? "PRACTICAL_TAKEAWAY" : rank % 2 == 0 ? "EMOTIONAL_BEAT" : "KEY_INSIGHT";
            }
        };
    }

    private String viewerIntent(String intentType, String text) {
        String topic = topicFromText(text);
        return switch (intentType) {
            case "ANSWER_THE_QUESTION" -> "Answer the exact question the audience is already asking about " + topic + ".";
            case "SOLVE_THE_PROBLEM" -> "Show the problem, the fix, and why the fix works.";
            case "LAND_THE_REVEAL" -> "Hold attention until the reveal changes how the viewer understands " + topic + ".";
            case "REFRAME_ASSUMPTION" -> "Start from a common assumption and flip it with source evidence.";
            case "BUILD_TO_PAYOFF" -> "Build tension quickly and end on the strongest payoff.";
            case "SETUP_PUNCHLINE" -> "Preserve the setup and punchline so the joke or surprise lands.";
            case "AVOID_THE_MISTAKE" -> "Help the viewer avoid a concrete mistake.";
            case "EXPLAIN_THE_WHY" -> "Explain the causal reason behind " + topic + ".";
            case "OPEN_CURIOSITY_LOOP" -> "Open with a strange or unanswered detail, then pay it off with source evidence.";
            case "ABSTRACT_INSIGHT" -> "Start from the bigger idea or pattern, then ground it in the source scene.";
            case "PRACTICAL_TAKEAWAY" -> "Give the viewer one usable action or takeaway.";
            case "EMOTIONAL_BEAT" -> "Tell the emotional turn without losing the source context.";
            default -> "Deliver one clear insight from the strongest source beat.";
        };
    }

    private String storyPromise(String intentType, List<Map<String, Object>> selected, CreatorShortVideo video, Map<String, Object> storyUnderstanding) {
        String title = video == null ? "" : stringValue(video.getTitle(), "");
        String first = firstText(selected);
        String last = lastText(selected);
        String source = nonBlank(title, first, last, stringValue(storyUnderstanding == null ? null : storyUnderstanding.get("summary"), ""));
        String topic = topicFromText(source);
        return switch (intentType) {
            case "ANSWER_THE_QUESTION" -> "The answer behind " + topic;
            case "SOLVE_THE_PROBLEM" -> "The problem and the fix";
            case "LAND_THE_REVEAL" -> "The reveal that changes the scene";
            case "REFRAME_ASSUMPTION" -> "The assumption that flips";
            case "SETUP_PUNCHLINE" -> "The setup and payoff";
            case "AVOID_THE_MISTAKE" -> "The mistake to avoid";
            case "OPEN_CURIOSITY_LOOP" -> "The strange detail behind " + topic;
            case "ABSTRACT_INSIGHT" -> "The bigger pattern behind " + topic;
            default -> truncate("The useful part about " + topic, 120);
        };
    }

    private String hookTypeFor(String intentType, String chainType, String text, int rank) {
        return switch (intentType) {
            case "ANSWER_THE_QUESTION", "EXPLAIN_THE_WHY" -> "Question Hook";
            case "SOLVE_THE_PROBLEM", "AVOID_THE_MISTAKE" -> "Problem/Solution";
            case "LAND_THE_REVEAL", "BUILD_TO_PAYOFF" -> "Reveal Hook";
            case "REFRAME_ASSUMPTION" -> "Contrarian Hook";
            case "SETUP_PUNCHLINE" -> "Punchline Hook";
            case "OPEN_CURIOSITY_LOOP" -> "Curiosity Gap";
            case "ABSTRACT_INSIGHT" -> "Abstract Hook";
            default -> {
                String normalized = text.toLowerCase(Locale.ROOT);
                if (normalized.contains("?")) {
                    yield "Question Hook";
                }
                if (normalized.matches(".*\\b(but|however|actually|instead)\\b.*")) {
                    yield "Contrarian Hook";
                }
                if ("PROBLEM_SOLUTION".equals(chainType)) {
                    yield "Problem/Solution";
                }
                yield rank % 2 == 0 ? "Aha Moment" : "Source Hook";
            }
        };
    }

    private String openingLineFor(String intentType, String firstText, String lastText, String promise) {
        String first = truncate(nonBlank(firstText, lastText, promise), 150);
        return switch (intentType) {
            case "ANSWER_THE_QUESTION" -> first.contains("?") ? first : "Here is the answer: " + first;
            case "SOLVE_THE_PROBLEM" -> "The problem is not what it looks like.";
            case "LAND_THE_REVEAL" -> "Wait for the reveal.";
            case "REFRAME_ASSUMPTION" -> "Most people read this wrong.";
            case "SETUP_PUNCHLINE" -> "The setup only works if you keep the ending.";
            case "AVOID_THE_MISTAKE" -> "Do not make this mistake.";
            case "EXPLAIN_THE_WHY" -> first.toLowerCase(Locale.ROOT).startsWith("why") ? first : "Here is why: " + first;
            case "OPEN_CURIOSITY_LOOP" -> first.contains("?") ? first : "The strange part is this: " + lowerFirst(first);
            case "ABSTRACT_INSIGHT" -> "The bigger idea is this: " + lowerFirst(first);
            default -> first;
        };
    }

    private String onScreenHook(String openingLine, String promise) {
        String base = truncate(nonBlank(openingLine, promise, "Watch the full beat"), 54);
        return base.endsWith(".") ? base.substring(0, base.length() - 1) : base;
    }

    private String titleFor(CreatorShortVideo video, Map<String, Object> intent, int rank) {
        String base = video == null ? "" : stringValue(video.getTitle(), "");
        String promise = stringValue(intent.get("storyPromise"), "");
        if (rank == 1 && !base.isBlank()) {
            return truncate(base, 180);
        }
        return truncate(nonBlank(promise, base, "Story beat") + " - intent " + rank, 180);
    }

    private String beatRole(int index, int count, String chainType, String text) {
        if (count <= 1) {
            return roleFromText(chainType, text, "CORE_BEAT");
        }
        if (index == 0) {
            return "SETUP";
        }
        if (index == count - 1) {
            return switch (stringValue(chainType, "")) {
                case "QUESTION_ANSWER" -> "ANSWER";
                case "PROBLEM_SOLUTION" -> "SOLUTION";
                case "PUNCHLINE" -> "PUNCHLINE";
                default -> "PAYOFF";
            };
        }
        return roleFromText(chainType, text, "EVIDENCE");
    }

    private String roleFromText(String chainType, String text, String fallback) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("?")) {
            return "QUESTION";
        }
        if (normalized.matches(".*\\b(problem|mistake|wrong|challenge|issue|struggle)\\b.*")) {
            return "PROBLEM";
        }
        if (normalized.matches(".*\\b(solution|fix|solve|because|therefore|so)\\b.*")) {
            return "EXPLANATION";
        }
        if (normalized.matches(".*\\b(reveal|actually|finally|turns out|result|truth)\\b.*")) {
            return "REVEAL";
        }
        if ("CONTRAST".equals(chainType)) {
            return "REFRAME";
        }
        return fallback;
    }

    private String beatReason(String role, String chainType) {
        return switch (role) {
            case "SETUP", "QUESTION", "PROBLEM" -> "Opening context required before the payoff can make sense.";
            case "ANSWER", "SOLUTION", "EXPLANATION" -> "Core answer or fix from the graph chain.";
            case "REVEAL", "PAYOFF", "PUNCHLINE" -> "Payoff beat that gives the short a complete ending.";
            case "REFRAME" -> "Contrast beat that flips the viewer assumption.";
            default -> "Evidence beat retained for story continuity.";
        };
    }

    private List<String> riskFlags(List<Map<String, Object>> selected, Map<String, Object> storyCritic) {
        Set<String> riskyNodeIds = storyRiskNodeIds(storyCritic);
        List<String> flags = new ArrayList<>();
        for (Map<String, Object> node : selected) {
            String nodeId = stringValue(node.get("id"), "");
            if (riskyNodeIds.contains(nodeId)) {
                flags.add("story_critic_context_required:" + nodeId);
            }
            String text = stringValue(node.get("transcript"), "").toLowerCase(Locale.ROOT);
            if (text.matches(".*\\b(this|that|he|she|they|it)\\b.*") && text.length() < 45) {
                flags.add("possible_pronoun_context:" + nodeId);
            }
        }
        return flags.stream().distinct().limit(8).toList();
    }

    private Set<String> storyRiskNodeIds(Map<String, Object> storyCritic) {
        Set<String> ids = new HashSet<>();
        collectNodeIds(ids, mapValue(storyCritic == null ? null : storyCritic.get("contextRisks")));
        collectNodeIds(ids, storyCritic == null ? null : storyCritic.get("riskNodeIds"));
        collectNodeIds(ids, storyCritic == null ? null : storyCritic.get("unsafeHookNodeIds"));
        collectNodeIds(ids, storyCritic == null ? null : storyCritic.get("nodesNeedingContext"));
        return ids;
    }

    private void collectNodeIds(Set<String> ids, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                collectNodeIds(ids, item);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> itemMap) {
                    Object nodeId = firstNonBlank(itemMap.get("nodeId"), itemMap.get("id"));
                    if (nodeId != null && !String.valueOf(nodeId).isBlank()) {
                        ids.add(String.valueOf(nodeId));
                    }
                } else if (item != null && !String.valueOf(item).isBlank()) {
                    ids.add(String.valueOf(item));
                }
            }
        }
    }

    private String detectedChainType(String text) {
        String normalized = stringValue(text, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("?") || normalized.matches(".*\\b(why|how|what|when|where|who)\\b.*")) {
            return "QUESTION_ANSWER";
        }
        if (normalized.matches(".*\\b(problem|mistake|issue|challenge|struggle|fix|solution|solve|staff|customer)\\b.*")) {
            return "PROBLEM_SOLUTION";
        }
        if (normalized.matches(".*\\b(reveal|secret|truth|finally|turns out|actually|result)\\b.*")) {
            return "REVEAL";
        }
        if (normalized.matches(".*\\b(but|however|instead|although|opposite|contrast)\\b.*")) {
            return "CONTRAST";
        }
        if (normalized.matches(".*\\b(punchline|joke|funny|laugh)\\b.*")) {
            return "PUNCHLINE";
        }
        return "INTERESTINGNESS";
    }

    private String operationFor(String chainType) {
        return switch (stringValue(chainType, "INTERESTINGNESS")) {
            case "QUESTION_ANSWER" -> "KEEP_QA_CHAIN";
            case "PROBLEM_SOLUTION" -> "KEEP_PROBLEM_SOLUTION_CHAIN";
            case "CAUSE_EFFECT", "REVEAL", "SETUP_PAYOFF" -> "KEEP_REVEAL_CHAIN";
            case "PUNCHLINE" -> "KEEP_PUNCHLINE_SETUP";
            case "ESCALATION" -> "KEEP_ESCALATION_CHAIN";
            case "CONTRAST" -> "KEEP_CONTRAST";
            default -> "KEEP_STORY_BEAT_CHAIN";
        };
    }

    private String chainLabel(String chainType) {
        String value = stringValue(chainType, "story beat").toLowerCase(Locale.ROOT).replace('_', ' ');
        return value.isBlank() ? "story beat" : value;
    }

    private boolean fits(double currentDuration, Map<String, Object> node, int targetDuration) {
        return currentDuration + nodeDuration(node) <= targetDuration + 0.75;
    }

    private void addNode(List<Map<String, Object>> selected, Map<String, Object> node) {
        if (node == null) {
            return;
        }
        String nodeId = stringValue(node.get("id"), "");
        if (nodeId.isBlank() || containsNode(selected, nodeId)) {
            return;
        }
        selected.add(node);
    }

    private boolean containsNode(List<Map<String, Object>> selected, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return false;
        }
        for (Map<String, Object> node : selected) {
            if (nodeId.equals(stringValue(node.get("id"), ""))) {
                return true;
            }
        }
        return false;
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

    private int averageInterestingness(List<Map<String, Object>> nodes, List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return 0;
        }
        int total = 0;
        int count = 0;
        for (Map<String, Object> node : nodes) {
            if (nodeIds.contains(stringValue(node.get("id"), ""))) {
                total += interestingness(node);
                count++;
            }
        }
        return count == 0 ? 0 : Math.round((float) total / count);
    }

    private int interestingness(Map<String, Object> node) {
        int existing = intValue(node == null ? null : node.get("interestingness"), 0);
        if (existing > 0) {
            return Math.max(0, Math.min(100, existing));
        }
        String text = stringValue(node == null ? null : node.get("transcript"), "").toLowerCase(Locale.ROOT);
        int score = 48;
        if (text.contains("?")) score += 12;
        if (text.matches(".*\\b(secret|mistake|problem|why|how|result|truth|money|growth|staff|customer|reveal|punchline)\\b.*")) score += 18;
        if (text.matches(".*\\b(strange|weird|mystery|hidden|surprising|impossible|ancient|resilient|unexpected|what if|abstract|idea|concept|pattern|system|biology|evolution)\\b.*")) score += 14;
        if (text.matches(".*\\b(but|actually|however|finally|because)\\b.*")) score += 8;
        if (text.length() < 18) score -= 8;
        if (text.length() > 90) score += 5;
        return Math.max(0, Math.min(100, score));
    }

    private List<String> acceptedWindowIds(ShortInterestingnessCriticService.InterestingnessCriticResult critic) {
        if (critic == null || critic.interestingnessCritic() == null) {
            return List.of();
        }
        return listOfStrings(critic.interestingnessCritic().get("acceptedWindowIds"));
    }

    private void ensureNodeIds(List<Map<String, Object>> nodes) {
        for (int index = 0; index < nodes.size(); index++) {
            Map<String, Object> node = nodes.get(index);
            if (stringValue(node.get("id"), "").isBlank()) {
                node.put("id", "n-%03d".formatted(index + 1));
            }
        }
    }

    private Map<String, Map<String, Object>> indexById(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> item : copyList(items)) {
            String id = stringValue(item.get("id"), "");
            if (!id.isBlank()) {
                result.put(id, item);
            }
        }
        return result;
    }

    private Map<String, Object> traceRow(String stage, String status, String summary, double confidence, Map<String, Object> metadata) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage);
        row.put("status", status);
        row.put("summary", summary);
        row.put("confidence", round3(confidence));
        row.put("agent", stage.toLowerCase(Locale.ROOT).replace('_', '-'));
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("metadata", metadata == null ? Map.of() : metadata);
        return row;
    }

    private Map<String, Object> caption(double start, double end, String text) {
        Map<String, Object> caption = new LinkedHashMap<>();
        caption.put("start", round3(Math.max(0.0, start)));
        caption.put("end", round3(Math.max(start + 0.1, end)));
        caption.put("text", truncate(text, 96));
        return caption;
    }

    private String firstText(List<Map<String, Object>> nodes) {
        return nodes.isEmpty() ? "" : stringValue(nodes.get(0).get("transcript"), "");
    }

    private String lastText(List<Map<String, Object>> nodes) {
        return nodes.isEmpty() ? "" : stringValue(nodes.get(nodes.size() - 1).get("transcript"), "");
    }

    private Object firstNonBlank(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String nonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
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

    private String topicFromText(String text) {
        String cleaned = stringValue(text, "")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return "this moment";
        }
        String[] words = cleaned.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            String safe = word.replaceAll("[^A-Za-z0-9'-]", "");
            if (safe.length() < 3) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(safe);
            if (builder.length() >= 42) {
                break;
            }
        }
        return builder.length() == 0 ? "this moment" : truncate(builder.toString(), 48);
    }

    private double firstNumber(List<Map<String, Object>> nodes, String key) {
        return nodes.isEmpty() ? 0.0 : round3(doubleValue(nodes.get(0).get(key), 0.0));
    }

    private double lastNumber(List<Map<String, Object>> nodes, String key) {
        return nodes.isEmpty() ? 0.0 : round3(doubleValue(nodes.get(nodes.size() - 1).get(key), 0.0));
    }

    private String candidateKey(Map<String, Object> candidate) {
        Map<String, Object> edl = mapValue(candidate.get("editDecisionList"));
        String key = edlKey(edl);
        if (!key.isBlank()) {
            return key;
        }
        return stringValue(candidate.get("title"), "") + ":" + stringValue(candidate.get("hookType"), "");
    }

    private String edlKey(Map<String, Object> edl) {
        return String.join("|", listOfMaps(edl.get("segments")).stream()
                .map(segment -> stringValue(segment.get("nodeId"), "") + "@" + stringValue(segment.get("sourceStart"), ""))
                .toList());
    }

    private int targetDuration(CreatorShortVideo video) {
        return Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());
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

    private List<String> listOfStrings(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), mapValue);
                }
            });
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 1)).trim() + "...";
    }

    private record StorySeed(
            String chainType,
            List<String> nodeIds,
            String anchorNodeId,
            int score,
            String reason,
            String windowId,
            String source
    ) {
    }

    public record StoryBeatPlanningResult(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> plans,
            List<Map<String, Object>> intents,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
