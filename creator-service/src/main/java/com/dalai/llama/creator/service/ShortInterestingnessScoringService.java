package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ShortInterestingnessScoringService {

    private static final Set<String> PROTECTED_EDGE_TYPES = Set.of(
            "QUESTION_ANSWER",
            "PROBLEM_SOLUTION",
            "CAUSE_EFFECT",
            "SETUP_PAYOFF",
            "ESCALATION",
            "CONTRAST",
            "REVEAL"
    );

    public InterestingnessScoringResult score(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> scenes,
            Map<String, Object> sceneCritic,
            Map<String, Object> storyUnderstanding,
            Map<String, Object> storyCritic
    ) {
        List<Map<String, Object>> nodes = copyList(transcript);
        nodes.sort(Comparator.comparingDouble(node -> doubleValue(node.get("start"), 0.0)));
        if (nodes.isEmpty()) {
            return skipped("No transcript nodes were available for robust interestingness scoring.");
        }

        Map<String, Map<String, Object>> sceneById = indexById(scenes);
        Map<String, Integer> ordinalByNodeId = ordinalByNodeId(nodes);
        Map<String, List<ChainEvidence>> chainsByNode = protectedChains(graph, storyUnderstanding, storyCritic, ordinalByNodeId);
        Set<String> riskNodes = riskNodeIds(storyCritic);
        Set<String> unsafeHookNodes = unsafeHookNodeIds(storyCritic);
        Set<String> visuallyRiskyScenes = visuallyRiskySceneIds(sceneCritic);

        List<Map<String, Object>> scoredTranscript = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            Map<String, Object> node = new LinkedHashMap<>(nodes.get(index));
            String nodeId = stringValue(node.get("id"), "n-%03d".formatted(index + 1));
            if (stringValue(node.get("id"), "").isBlank()) {
                node.put("id", nodeId);
            }
            Map<String, Object> scene = sceneById.get(stringValue(node.get("sceneId"), ""));
            NodeScore nodeScore = scoreNode(
                    video,
                    videoDna,
                    node,
                    scene,
                    chainsByNode.getOrDefault(nodeId, List.of()),
                    riskNodes.contains(nodeId),
                    unsafeHookNodes.contains(nodeId),
                    visuallyRiskyScenes.contains(stringValue(node.get("sceneId"), ""))
            );
            node.put("interestingness", nodeScore.score());
            node.put("interestingnessScorecard", nodeScore.scorecard());
            scoredTranscript.add(node);
        }

        List<Map<String, Object>> topMoments = topMoments(scoredTranscript, 36);
        List<Map<String, Object>> candidateWindows = candidateWindows(video, scoredTranscript, topMoments, chainsByNode);
        Map<String, Object> interestingness = interestingnessSummary(scoredTranscript, topMoments, candidateWindows);
        Map<String, Object> critic = critic(scoredTranscript, candidateWindows, riskNodes, visuallyRiskyScenes);

        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceRow(
                "INTERESTINGNESS",
                candidateWindows.isEmpty() ? "WARN" : "COMPLETED",
                candidateWindows.isEmpty()
                        ? "Robust interestingness worker scored transcript beats but found no usable candidate windows."
                        : "Robust interestingness worker scored transcript beats and ranked timestamp windows from story, scene, graph, and risk evidence.",
                candidateWindows.isEmpty() ? 0.42 : 0.88,
                traceMetadata(scoredTranscript.size(), topMoments.size(), candidateWindows.size(), interestingness)
        ));
        trace.add(traceRow(
                "INTERESTINGNESS_CRITIC",
                stringValue(critic.get("status"), "WARN"),
                stringValue(critic.get("summary"), "Interestingness critic completed."),
                doubleValue(critic.get("confidence"), 0.7),
                critic
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "deterministic_robust_interestingness_worker");
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("evidenceBacked", true);
        metadata.put("transcriptNodeCount", scoredTranscript.size());
        metadata.put("sceneCount", scenes == null ? 0 : scenes.size());
        metadata.put("candidateWindowCount", candidateWindows.size());
        metadata.put("weights", scoringWeights());
        metadata.put("dimensions", List.of(
                "hookClarity",
                "tensionConflict",
                "payoffReveal",
                "novelty",
                "specificity",
                "emotionEnergy",
                "visualSupport",
                "storyChainValue",
                "compressionFit",
                "riskPenalty"
        ));

        return new InterestingnessScoringResult(
                true,
                scoredTranscript,
                topMoments,
                candidateWindows,
                interestingness,
                critic,
                trace,
                metadata
        );
    }

    private NodeScore scoreNode(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            Map<String, Object> node,
            Map<String, Object> scene,
            List<ChainEvidence> chains,
            boolean storyRisk,
            boolean unsafeHook,
            boolean visualRisk
    ) {
        String text = stringValue(node.get("transcript"), "");
        String normalized = text.toLowerCase(Locale.ROOT);
        double duration = Math.max(0.1, doubleValue(node.get("end"), 0.0) - doubleValue(node.get("start"), 0.0));
        List<String> evidence = new ArrayList<>();
        List<String> penalties = new ArrayList<>();

        int existingSignal = clamp(intValue(node.get("interestingness"), 0), 0, 100);
        int hook = hookScore(normalized, evidence);
        int tension = tensionScore(normalized, evidence);
        int payoff = payoffScore(normalized, evidence);
        int novelty = noveltyScore(normalized, evidence);
        int specificity = specificityScore(text, evidence);
        int emotion = emotionScore(node, normalized, evidence);
        int visual = visualScore(node, scene, visualRisk, evidence, penalties);
        int story = storyScore(chains, storyRisk, evidence, penalties);
        int compression = compressionFitScore(video, duration, normalized, unsafeHook, evidence, penalties);
        int platform = platformScore(video, duration, text);
        int penalty = riskPenalty(normalized, duration, storyRisk, unsafeHook, visualRisk, penalties);

        double weighted = existingSignal * 0.08
                + hook * 0.14
                + tension * 0.13
                + payoff * 0.14
                + novelty * 0.12
                + specificity * 0.09
                + emotion * 0.09
                + visual * 0.08
                + story * 0.08
                + compression * 0.04
                + platform * 0.01;
        int score = clamp((int) Math.round(weighted - penalty), 0, 100);

        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("existingSignal", existingSignal);
        dimensions.put("hookClarity", hook);
        dimensions.put("tensionConflict", tension);
        dimensions.put("payoffReveal", payoff);
        dimensions.put("novelty", novelty);
        dimensions.put("specificity", specificity);
        dimensions.put("emotionEnergy", emotion);
        dimensions.put("visualSupport", visual);
        dimensions.put("storyChainValue", story);
        dimensions.put("compressionFit", compression);
        dimensions.put("platformFit", platform);
        dimensions.put("riskPenalty", penalty);

        Map<String, Object> scorecard = new LinkedHashMap<>();
        scorecard.put("score", score);
        scorecard.put("status", score >= 75 ? "STRONG" : score >= 60 ? "USABLE" : score >= 45 ? "CONTEXT_ONLY" : "WEAK");
        scorecard.put("dimensions", dimensions);
        scorecard.put("evidence", evidence.stream().distinct().limit(8).toList());
        scorecard.put("penalties", penalties.stream().distinct().limit(8).toList());
        scorecard.put("chainEvidence", chains.stream().map(ChainEvidence::toMap).limit(6).toList());
        scorecard.put("compressionSafe", penalty < 25 && !unsafeHook);
        scorecard.put("videoType", stringValue(videoDna == null ? null : videoDna.get("primaryType"), "unknown"));
        scorecard.put("source", "deterministic_robust_interestingness_worker");
        return new NodeScore(score, scorecard);
    }

    private List<Map<String, Object>> candidateWindows(
            CreatorShortVideo video,
            List<Map<String, Object>> scoredTranscript,
            List<Map<String, Object>> topMoments,
            Map<String, List<ChainEvidence>> chainsByNode
    ) {
        int target = Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());
        int requested = Math.max(1, Math.min(video == null || video.getRequestedShorts() == null ? 20 : video.getRequestedShorts(), 50));
        int limit = Math.max(12, requested * 2);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : scoredTranscript) {
            byId.put(stringValue(node.get("id"), ""), node);
        }

        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> windows = new ArrayList<>();
        for (Map<String, Object> moment : topMoments) {
            if (windows.size() >= limit) {
                break;
            }
            String anchorId = stringValue(moment.get("nodeId"), "");
            Map<String, Object> anchor = byId.get(anchorId);
            if (anchor == null) {
                continue;
            }
            List<Map<String, Object>> selected = selectedWindowNodes(scoredTranscript, anchor, chainsByNode.getOrDefault(anchorId, List.of()), target);
            if (selected.isEmpty()) {
                continue;
            }
            List<String> nodeIds = selected.stream().map(node -> stringValue(node.get("id"), "")).filter(id -> !id.isBlank()).toList();
            String key = String.join(",", nodeIds);
            if (seen.add(key)) {
                windows.add(windowPayload(windows.size() + 1, anchor, selected, chainsByNode.getOrDefault(anchorId, List.of()), target));
            }
        }
        windows.sort(Comparator.comparingInt((Map<String, Object> item) -> intValue(item.get("score"), 0)).reversed());
        int rank = 1;
        for (Map<String, Object> window : windows) {
            window.put("rank", rank++);
        }
        return windows;
    }

    private List<Map<String, Object>> selectedWindowNodes(
            List<Map<String, Object>> scoredTranscript,
            Map<String, Object> anchor,
            List<ChainEvidence> chains,
            int target
    ) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : scoredTranscript) {
            byId.put(stringValue(node.get("id"), ""), node);
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        addNode(selected, anchor);
        for (ChainEvidence chain : chains) {
            for (String nodeId : chain.nodeIds()) {
                Map<String, Object> node = byId.get(nodeId);
                if (node != null && duration(selected) + nodeDuration(node) <= target + 1.5) {
                    addNode(selected, node);
                }
            }
        }
        int anchorIndex = scoredTranscript.indexOf(anchor);
        if (anchorIndex > 0 && duration(selected) < target * 0.70) {
            Map<String, Object> previous = scoredTranscript.get(anchorIndex - 1);
            if (duration(selected) + nodeDuration(previous) <= target + 0.75) {
                addNode(selected, previous);
            }
        }
        if (anchorIndex >= 0 && anchorIndex < scoredTranscript.size() - 1 && duration(selected) < target * 0.92) {
            Map<String, Object> next = scoredTranscript.get(anchorIndex + 1);
            if (duration(selected) + nodeDuration(next) <= target + 0.75) {
                addNode(selected, next);
            }
        }
        selected.sort(Comparator.comparingDouble(node -> doubleValue(node.get("start"), 0.0)));
        return selected;
    }

    private Map<String, Object> windowPayload(
            int rank,
            Map<String, Object> anchor,
            List<Map<String, Object>> selected,
            List<ChainEvidence> chains,
            int target
    ) {
        double start = selected.stream().mapToDouble(node -> doubleValue(node.get("start"), 0.0)).min().orElse(0.0);
        double end = selected.stream().mapToDouble(node -> doubleValue(node.get("end"), 0.0)).max().orElse(start);
        double duration = Math.max(0.1, end - start);
        int averageScore = (int) Math.round(selected.stream().mapToInt(node -> intValue(node.get("interestingness"), 0)).average().orElse(0.0));
        int anchorScore = intValue(anchor.get("interestingness"), averageScore);
        int score = clamp((int) Math.round(anchorScore * 0.65 + averageScore * 0.35 + Math.min(6, chains.size() * 2)), 0, 100);
        List<String> nodeIds = selected.stream().map(node -> stringValue(node.get("id"), "")).filter(id -> !id.isBlank()).toList();
        List<String> sceneIds = selected.stream().map(node -> stringValue(node.get("sceneId"), "")).filter(id -> !id.isBlank()).distinct().toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("windowId", "interest-window-%03d".formatted(rank));
        payload.put("rank", rank);
        payload.put("nodeIds", nodeIds);
        payload.put("sceneIds", sceneIds);
        payload.put("anchorNodeId", stringValue(anchor.get("id"), ""));
        payload.put("start", round3(start));
        payload.put("end", round3(end));
        payload.put("durationSeconds", round3(duration));
        payload.put("targetDurationSeconds", target);
        payload.put("score", score);
        payload.put("hookCandidate", firstText(selected));
        payload.put("payoffCandidate", lastText(selected));
        payload.put("chainType", chains.isEmpty() ? "INTERESTINGNESS" : chains.get(0).type());
        payload.put("compressionStrategy", operationFor(chains.isEmpty() ? "INTERESTINGNESS" : chains.get(0).type()));
        payload.put("reason", windowReason(anchor, chains));
        payload.put("evidence", selected.stream()
                .map(node -> mapValue(node.get("interestingnessScorecard")))
                .flatMap(scorecard -> listOfStrings(scorecard.get("evidence")).stream())
                .distinct()
                .limit(10)
                .toList());
        payload.put("riskFlags", selected.stream()
                .map(node -> mapValue(node.get("interestingnessScorecard")))
                .flatMap(scorecard -> listOfStrings(scorecard.get("penalties")).stream())
                .distinct()
                .limit(8)
                .toList());
        payload.put("chainEvidence", chains.stream().map(ChainEvidence::toMap).limit(6).toList());
        return payload;
    }

    private List<Map<String, Object>> topMoments(List<Map<String, Object>> scoredTranscript, int limit) {
        return scoredTranscript.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> node) -> intValue(node.get("interestingness"), 0)).reversed())
                .limit(limit)
                .map(node -> {
                    Map<String, Object> moment = new LinkedHashMap<>();
                    moment.put("nodeId", node.get("id"));
                    moment.put("sceneId", node.getOrDefault("sceneId", ""));
                    moment.put("start", node.get("start"));
                    moment.put("end", node.get("end"));
                    moment.put("score", node.get("interestingness"));
                    moment.put("transcript", truncate(stringValue(node.get("transcript"), ""), 240));
                    moment.put("scorecard", node.getOrDefault("interestingnessScorecard", Map.of()));
                    return moment;
                })
                .toList();
    }

    private Map<String, Object> interestingnessSummary(
            List<Map<String, Object>> scoredTranscript,
            List<Map<String, Object>> topMoments,
            List<Map<String, Object>> candidateWindows
    ) {
        List<Integer> scores = scoredTranscript.stream().map(node -> intValue(node.get("interestingness"), 0)).sorted().toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", "deterministic_robust_interestingness_worker");
        summary.put("nodeCount", scoredTranscript.size());
        summary.put("topScore", scores.isEmpty() ? 0 : scores.get(scores.size() - 1));
        summary.put("medianScore", percentile(scores, 0.50));
        summary.put("p90Score", percentile(scores, 0.90));
        summary.put("highScoreNodeCount", scoredTranscript.stream().filter(node -> intValue(node.get("interestingness"), 0) >= 75).count());
        summary.put("usableNodeCount", scoredTranscript.stream().filter(node -> intValue(node.get("interestingness"), 0) >= 60).count());
        summary.put("topMoments", topMoments);
        summary.put("candidateWindows", candidateWindows);
        return summary;
    }

    private Map<String, Object> critic(
            List<Map<String, Object>> scoredTranscript,
            List<Map<String, Object>> candidateWindows,
            Set<String> riskNodes,
            Set<String> visuallyRiskyScenes
    ) {
        List<Integer> scores = scoredTranscript.stream().map(node -> intValue(node.get("interestingness"), 0)).sorted().toList();
        int top = scores.isEmpty() ? 0 : scores.get(scores.size() - 1);
        int p90 = percentile(scores, 0.90);
        int median = percentile(scores, 0.50);
        long strong = scoredTranscript.stream().filter(node -> intValue(node.get("interestingness"), 0) >= 75).count();
        List<String> issues = new ArrayList<>();
        if (candidateWindows.isEmpty()) issues.add("No candidate windows were produced.");
        if (top < 60) issues.add("No strong high-interest beat found.");
        if (p90 - median < 10 && scoredTranscript.size() > 5) issues.add("Score distribution is flat; ranking confidence is lower.");
        if (riskNodes.size() > Math.max(3, scoredTranscript.size() / 4)) issues.add("Many story nodes are context-risky and need chain preservation.");
        if (!visuallyRiskyScenes.isEmpty()) issues.add("Some scenes have visual/transition risk and should not be used as blind cut points.");
        String status = candidateWindows.isEmpty() || top < 45 ? "FAIL" : issues.isEmpty() ? "PASS" : "WARN";
        double confidence = clampDouble(0.35 + (top / 100.0) * 0.35 + Math.min(0.20, candidateWindows.size() / 50.0) + Math.min(0.10, strong / 25.0), 0.0, 0.96);

        Map<String, Object> critic = new LinkedHashMap<>();
        critic.put("status", status);
        critic.put("confidence", round3(confidence));
        critic.put("summary", issues.isEmpty()
                ? "Interestingness scoring has enough spread and evidence for ranked compression."
                : "Interestingness scoring completed with warnings: " + String.join(" ", issues));
        critic.put("issues", issues);
        critic.put("scoreDistribution", Map.of(
                "top", top,
                "p90", p90,
                "median", median,
                "strongNodeCount", strong,
                "candidateWindowCount", candidateWindows.size()
        ));
        critic.put("riskNodeCount", riskNodes.size());
        critic.put("visuallyRiskySceneCount", visuallyRiskyScenes.size());
        critic.put("weights", scoringWeights());
        return critic;
    }

    private int hookScore(String normalized, List<String> evidence) {
        int score = 0;
        if (normalized.contains("?")) {
            score += 55;
            evidence.add("Question hook.");
        }
        if (matches(normalized, "\\b(why|how|what|when|where|who|should|can|did|does)\\b")) {
            score += 30;
            evidence.add("Curiosity phrase.");
        }
        if (matches(normalized, "\\b(wait|look|listen|imagine|here's|this is|the thing is)\\b")) {
            score += 18;
            evidence.add("Attention opener.");
        }
        return clamp(score, 0, 100);
    }

    private int tensionScore(String normalized, List<String> evidence) {
        int score = 0;
        if (matches(normalized, "\\b(problem|issue|mistake|failed|struggle|challenge|risk|pain|hard|wrong|lost|fired|staff|customer|complaint)\\b")) {
            score += 58;
            evidence.add("Conflict or problem signal.");
        }
        if (matches(normalized, "\\b(but|however|instead|except|unless|even though|actually)\\b")) {
            score += 25;
            evidence.add("Contrast/reframe signal.");
        }
        if (matches(normalized, "\\b(secret|truth|nobody|hidden|surprise|unexpected)\\b")) {
            score += 22;
            evidence.add("Curiosity/tension word.");
        }
        return clamp(score, 0, 100);
    }

    private int payoffScore(String normalized, List<String> evidence) {
        int score = 0;
        if (matches(normalized, "\\b(result|results|finally|therefore|so|because|that's why|turns out|what happened|payoff)\\b")) {
            score += 52;
            evidence.add("Payoff/result signal.");
        }
        if (matches(normalized, "\\b(solved|fixed|answer|learned|changed|grew|saved|earned|won|lost)\\b")) {
            score += 34;
            evidence.add("Outcome or answer signal.");
        }
        if (matches(normalized, "\\b(lesson|takeaway|moral|proof|example)\\b")) {
            score += 18;
            evidence.add("Takeaway/proof signal.");
        }
        return clamp(score, 0, 100);
    }

    private int noveltyScore(String normalized, List<String> evidence) {
        int score = 0;
        if (matches(normalized, "\\b(first|only|never|always|best|worst|most|least|new|old|rare|weird|crazy|unexpected)\\b")) {
            score += 40;
            evidence.add("Novelty/superlative signal.");
        }
        if (matches(normalized, "\\b(money|revenue|growth|views|followers|sales|cost|price|profit|loss|roi|conversion)\\b")) {
            score += 30;
            evidence.add("Business/value signal.");
        }
        if (matches(normalized, "\\b(ai|automation|brand|creator|product|team|staff|customer|client)\\b")) {
            score += 20;
            evidence.add("Audience-relevant topic signal.");
        }
        return clamp(score, 0, 100);
    }

    private int specificityScore(String text, List<String> evidence) {
        String normalized = text.toLowerCase(Locale.ROOT);
        int score = 0;
        if (text.matches(".*\\d+.*")) {
            score += 38;
            evidence.add("Concrete number.");
        }
        if (matches(normalized, "\\b(rupee|rs|inr|usd|dollar|percent|%|day|week|month|year|minute|second)\\b")) {
            score += 26;
            evidence.add("Specific measurable detail.");
        }
        int wordCount = wordCount(text);
        if (wordCount >= 8 && wordCount <= 42) score += 24;
        else if (wordCount > 42) score += 12;
        if (text.matches(".*[A-Z][a-z]+ [A-Z][a-z]+.*")) {
            score += 12;
            evidence.add("Named entity detail.");
        }
        return clamp(score, 0, 100);
    }

    private int emotionScore(Map<String, Object> node, String normalized, List<String> evidence) {
        int score = 0;
        score += Math.min(45, Math.max(0, intValue(node.get("emotion"), 0)) / 2);
        score += Math.min(30, Math.max(0, intValue(node.get("motion"), 0)) / 3);
        if (matches(normalized, "\\b(love|hate|angry|happy|sad|excited|afraid|shock|shocked|funny|laughed|cry|proud)\\b")) {
            score += 30;
            evidence.add("Emotion word.");
        }
        if (normalized.contains("!")) {
            score += 10;
            evidence.add("Exclamation/emphasis.");
        }
        return clamp(score, 0, 100);
    }

    private int visualScore(Map<String, Object> node, Map<String, Object> scene, boolean visualRisk, List<String> evidence, List<String> penalties) {
        int score = 25;
        if (!stringValue(node.get("sceneId"), "").isBlank()) score += 20;
        if (!listOfStrings(node.get("frames")).isEmpty()) {
            score += 25;
            evidence.add("Frame-backed transcript node.");
        }
        if (scene != null && !scene.isEmpty()) {
            score += 15;
            if (!stringValue(scene.get("representativeFrameId"), "").isBlank()) {
                score += 10;
                evidence.add("Representative scene frame available.");
            }
        }
        if (visualRisk) {
            score -= 30;
            penalties.add("Scene has visual/transition risk.");
        }
        return clamp(score, 0, 100);
    }

    private int storyScore(List<ChainEvidence> chains, boolean risky, List<String> evidence, List<String> penalties) {
        int score = 28;
        if (!chains.isEmpty()) {
            score += Math.min(60, 25 + chains.size() * 12);
            evidence.add("Protected story chain evidence.");
        }
        if (risky) {
            score -= 18;
            penalties.add("Story critic marked context risk.");
        }
        return clamp(score, 0, 100);
    }

    private int compressionFitScore(CreatorShortVideo video, double duration, String normalized, boolean unsafeHook, List<String> evidence, List<String> penalties) {
        int target = Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());
        int score = 45;
        if (duration >= 1.2 && duration <= Math.min(18, target)) {
            score += 28;
            evidence.add("Duration fits short-form window building.");
        }
        if (normalized.endsWith(".") || normalized.endsWith("?") || normalized.endsWith("!")) score += 10;
        if (unsafeHook) {
            score -= 35;
            penalties.add("Unsafe as standalone hook.");
        }
        if (duration > target * 0.8) {
            score -= 22;
            penalties.add("Single node consumes too much target duration.");
        }
        return clamp(score, 0, 100);
    }

    private int platformScore(CreatorShortVideo video, double duration, String text) {
        int target = Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());
        int words = wordCount(text);
        int score = 60;
        if (duration <= target && duration >= 1.0) score += 20;
        if (words >= 5 && words <= 38) score += 20;
        return clamp(score, 0, 100);
    }

    private int riskPenalty(String normalized, double duration, boolean storyRisk, boolean unsafeHook, boolean visualRisk, List<String> penalties) {
        int penalty = 0;
        if (matches(normalized, "\\b(um|uh|like and subscribe|subscribe|welcome back|don't forget to)\\b")) {
            penalty += 16;
            penalties.add("Intro/filler/subscription language.");
        }
        if (wordCount(normalized) < 4) {
            penalty += 12;
            penalties.add("Too little transcript context.");
        }
        if (duration < 0.8) {
            penalty += 12;
            penalties.add("Very short beat.");
        }
        if (storyRisk) penalty += 18;
        if (unsafeHook) penalty += 16;
        if (visualRisk) penalty += 12;
        if (matches(normalized, "\\b(this|that|he|she|they|it)\\b") && !matches(normalized, "\\b(because|why|how|result|problem|solution|answer)\\b")) {
            penalty += 8;
            penalties.add("Pronoun-heavy beat may need prior context.");
        }
        return clamp(penalty, 0, 45);
    }

    private Map<String, List<ChainEvidence>> protectedChains(
            Map<String, Object> graph,
            Map<String, Object> storyUnderstanding,
            Map<String, Object> storyCritic,
            Map<String, Integer> ordinalByNodeId
    ) {
        Map<String, List<ChainEvidence>> result = new HashMap<>();
        for (Map<String, Object> edge : listOfMaps(graph == null ? null : graph.get("edges"))) {
            String type = stringValue(edge.get("type"), "");
            String from = stringValue(edge.get("from"), "");
            String to = stringValue(edge.get("to"), "");
            if (!PROTECTED_EDGE_TYPES.contains(type) || from.isBlank() || to.isBlank() || from.startsWith("scene-") || to.startsWith("scene-")) {
                continue;
            }
            addChain(result, new ChainEvidence(type, ordered(List.of(from, to), ordinalByNodeId), stringValue(edge.get("reason"), "Protected graph edge."), "graph_edge"));
        }
        for (Map<String, Object> chain : listOfMaps(storyUnderstanding == null ? null : storyUnderstanding.get("mustPreserveChains"))) {
            List<String> nodeIds = ordered(listOfStrings(chain.get("nodeIds")), ordinalByNodeId);
            if (!nodeIds.isEmpty()) {
                addChain(result, new ChainEvidence(stringValue(chain.get("type"), "STORY_CHAIN").toUpperCase(Locale.ROOT), nodeIds, stringValue(chain.get("reason"), "Story critic preservation chain."), "story_understanding"));
            }
        }
        for (Map<String, Object> rule : listOfMaps(storyCritic == null ? null : storyCritic.get("preservationRules"))) {
            List<String> nodeIds = ordered(listOfStrings(rule.get("nodeIds")), ordinalByNodeId);
            if (!nodeIds.isEmpty()) {
                addChain(result, new ChainEvidence("PRESERVATION_RULE", nodeIds, stringValue(rule.get("reason"), stringValue(rule.get("rule"), "Story critic preservation rule.")), "story_critic"));
            }
        }
        return result;
    }

    private void addChain(Map<String, List<ChainEvidence>> result, ChainEvidence chain) {
        for (String nodeId : chain.nodeIds()) {
            result.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(chain);
        }
    }

    private Set<String> riskNodeIds(Map<String, Object> storyCritic) {
        Set<String> result = new HashSet<>();
        for (Map<String, Object> item : listOfMaps(storyCritic == null ? null : storyCritic.get("missingContext"))) {
            result.addAll(listOfStrings(item.get("nodeIds")));
            String nodeId = stringValue(item.get("nodeId"), "");
            if (!nodeId.isBlank()) result.add(nodeId);
        }
        for (Map<String, Object> item : listOfMaps(storyCritic == null ? null : storyCritic.get("brokenChains"))) {
            result.addAll(listOfStrings(item.get("nodeIds")));
        }
        Map<String, Object> guidance = mapValue(storyCritic == null ? null : storyCritic.get("candidateGuidance"));
        result.addAll(listOfStrings(guidance.get("avoidStandaloneNodes")));
        return result;
    }

    private Set<String> unsafeHookNodeIds(Map<String, Object> storyCritic) {
        Map<String, Object> guidance = mapValue(storyCritic == null ? null : storyCritic.get("candidateGuidance"));
        return new HashSet<>(listOfStrings(guidance.get("unsafeHookNodes")));
    }

    private Set<String> visuallyRiskySceneIds(Map<String, Object> sceneCritic) {
        Set<String> result = new HashSet<>();
        for (String key : List.of("badCutScenes", "unsafeCutScenes", "lowQualityScenes", "transitionRiskScenes")) {
            result.addAll(listOfStrings(sceneCritic == null ? null : sceneCritic.get(key)));
        }
        for (Map<String, Object> issue : listOfMaps(sceneCritic == null ? null : sceneCritic.get("issues"))) {
            String sceneId = stringValue(issue.get("sceneId"), "");
            String severity = stringValue(issue.get("severity"), "").toLowerCase(Locale.ROOT);
            if (!sceneId.isBlank() && (severity.contains("high") || severity.contains("medium"))) {
                result.add(sceneId);
            }
        }
        return result;
    }

    private Map<String, Object> scoringWeights() {
        Map<String, Object> weights = new LinkedHashMap<>();
        weights.put("existingSignal", 0.08);
        weights.put("hookClarity", 0.14);
        weights.put("tensionConflict", 0.13);
        weights.put("payoffReveal", 0.14);
        weights.put("novelty", 0.12);
        weights.put("specificity", 0.09);
        weights.put("emotionEnergy", 0.09);
        weights.put("visualSupport", 0.08);
        weights.put("storyChainValue", 0.08);
        weights.put("compressionFit", 0.04);
        weights.put("platformFit", 0.01);
        weights.put("riskPenalty", "subtract up to 45");
        return weights;
    }

    private InterestingnessScoringResult skipped(String reason) {
        Map<String, Object> critic = new LinkedHashMap<>();
        critic.put("status", "WARN");
        critic.put("confidence", 0.2);
        critic.put("summary", reason);
        critic.put("issues", List.of(reason));
        List<Map<String, Object>> trace = List.of(
                traceRow("INTERESTINGNESS", "WARN", reason, 0.2, Map.of("source", "deterministic_robust_interestingness_worker")),
                traceRow("INTERESTINGNESS_CRITIC", "WARN", reason, 0.2, critic)
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "deterministic_robust_interestingness_worker");
        metadata.put("evidenceBacked", false);
        metadata.put("reason", reason);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        return new InterestingnessScoringResult(false, List.of(), List.of(), List.of(), Map.of(), critic, trace, metadata);
    }

    private String operationFor(String chainType) {
        return switch (stringValue(chainType, "INTERESTINGNESS")) {
            case "QUESTION_ANSWER" -> "KEEP_QA_CHAIN";
            case "PROBLEM_SOLUTION" -> "KEEP_PROBLEM_SOLUTION_CHAIN";
            case "CAUSE_EFFECT", "SETUP_PAYOFF", "REVEAL" -> "KEEP_REVEAL_CHAIN";
            case "PRESERVATION_RULE" -> "KEEP_STORY_PRESERVATION_RULE";
            case "ESCALATION" -> "KEEP_ESCALATION_CHAIN";
            case "CONTRAST" -> "KEEP_CONTRAST";
            default -> "KEEP_INTERESTING_CHAIN";
        };
    }

    private String firstText(List<Map<String, Object>> selected) {
        for (Map<String, Object> node : selected) {
            String text = stringValue(node.get("transcript"), "");
            if (!text.isBlank()) return truncate(text, 140);
        }
        return "";
    }

    private String lastText(List<Map<String, Object>> selected) {
        for (int index = selected.size() - 1; index >= 0; index--) {
            String text = stringValue(selected.get(index).get("transcript"), "");
            if (!text.isBlank()) return truncate(text, 140);
        }
        return "";
    }

    private String windowReason(Map<String, Object> anchor, List<ChainEvidence> chains) {
        String base = "Anchored on node " + stringValue(anchor.get("id"), "") + " with score " + intValue(anchor.get("interestingness"), 0) + ".";
        if (chains.isEmpty()) {
            return base + " Selected as a standalone high-interest beat with neighbor context.";
        }
        return base + " Preserves " + chains.get(0).type().toLowerCase(Locale.ROOT).replace('_', ' ') + " chain context.";
    }

    private Map<String, Object> traceMetadata(int nodeCount, int topMomentCount, int candidateWindowCount, Map<String, Object> interestingness) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "deterministic_robust_interestingness_worker");
        metadata.put("nodeCount", nodeCount);
        metadata.put("topMomentCount", topMomentCount);
        metadata.put("candidateWindowCount", candidateWindowCount);
        metadata.put("topScore", interestingness.getOrDefault("topScore", 0));
        metadata.put("p90Score", interestingness.getOrDefault("p90Score", 0));
        metadata.put("medianScore", interestingness.getOrDefault("medianScore", 0));
        return metadata;
    }

    private void addNode(List<Map<String, Object>> selected, Map<String, Object> node) {
        String nodeId = stringValue(node.get("id"), "");
        for (Map<String, Object> existing : selected) {
            if (nodeId.equals(stringValue(existing.get("id"), ""))) return;
        }
        selected.add(node);
    }

    private double duration(List<Map<String, Object>> nodes) {
        double total = 0.0;
        for (Map<String, Object> node : nodes) total += nodeDuration(node);
        return total;
    }

    private double nodeDuration(Map<String, Object> node) {
        double start = doubleValue(node.get("start"), 0.0);
        double end = Math.max(start, doubleValue(node.get("end"), start));
        return Math.max(0.1, end - start);
    }

    private int percentile(List<Integer> sortedScores, double percentile) {
        if (sortedScores == null || sortedScores.isEmpty()) return 0;
        int index = clamp((int) Math.round((sortedScores.size() - 1) * percentile), 0, sortedScores.size() - 1);
        return sortedScores.get(index);
    }

    private Map<String, Map<String, Object>> indexById(List<Map<String, Object>> values) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> value : copyList(values)) {
            String id = stringValue(value.get("id"), "");
            if (!id.isBlank()) result.put(id, value);
        }
        return result;
    }

    private Map<String, Integer> ordinalByNodeId(List<Map<String, Object>> nodes) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            String id = stringValue(nodes.get(index).get("id"), "");
            if (!id.isBlank()) result.put(id, index);
        }
        return result;
    }

    private List<String> ordered(List<String> ids, Map<String, Integer> ordinalByNodeId) {
        return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted(Comparator.comparingInt(id -> ordinalByNodeId.getOrDefault(id, Integer.MAX_VALUE)))
                .toList();
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> value) {
        if (value == null) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : value) result.add(item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item));
        return result;
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    map.forEach((key, mapValue) -> {
                        if (key != null) normalized.put(String.valueOf(key), mapValue);
                    });
                    if (!normalized.isEmpty()) result.add(normalized);
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
                if (key != null) result.put(String.valueOf(key), mapValue);
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
                if (!text.isBlank()) result.add(text);
            }
        } else {
            String text = stringValue(value, "");
            if (!text.isBlank()) result.add(text);
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

    private boolean matches(String value, String regex) {
        return value != null && value.matches(".*" + regex + ".*");
    }

    private int wordCount(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) return 0;
        return text.split("\\s+").length;
    }

    private String truncate(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= maxLength) return text;
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
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

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record NodeScore(int score, Map<String, Object> scorecard) {
    }

    private record ChainEvidence(String type, List<String> nodeIds, String reason, String source) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("nodeIds", nodeIds);
            map.put("reason", reason);
            map.put("source", source);
            return map;
        }
    }

    public record InterestingnessScoringResult(
            boolean evidenceBacked,
            List<Map<String, Object>> scoredTranscript,
            List<Map<String, Object>> topMoments,
            List<Map<String, Object>> candidateWindows,
            Map<String, Object> interestingness,
            Map<String, Object> interestingnessCritic,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
