package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ShortInterestingnessCriticService {

    public InterestingnessCriticResult critique(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            ShortInterestingnessScoringService.InterestingnessScoringResult scoring,
            Map<String, Object> graph,
            Map<String, Object> sceneCritic,
            Map<String, Object> storyCritic
    ) {
        List<Map<String, Object>> scoredTranscript = copyList(scoring == null ? null : scoring.scoredTranscript());
        List<Map<String, Object>> topMoments = copyList(scoring == null ? null : scoring.topMoments());
        List<Map<String, Object>> candidateWindows = copyList(scoring == null ? null : scoring.candidateWindows());
        Map<String, Object> interestingness = mutableMap(scoring == null ? null : scoring.interestingness());

        int requested = Math.max(1, Math.min(video == null || video.getRequestedShorts() == null ? 20 : video.getRequestedShorts(), 50));
        int targetDuration = Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());
        Set<String> storyRiskNodes = storyRiskNodeIds(storyCritic);
        Set<String> visuallyRiskyScenes = visuallyRiskySceneIds(sceneCritic);

        Map<String, Object> distributionAudit = auditDistribution(scoredTranscript);
        Map<String, Object> scorecardAudit = auditScorecards(scoredTranscript, topMoments);
        Map<String, Object> evidenceAudit = auditEvidence(topMoments);
        Map<String, Object> rankingAudit = auditRanking(candidateWindows, requested);
        Map<String, Object> contextAudit = auditContextSafety(candidateWindows, storyRiskNodes);
        Map<String, Object> visualAudit = auditVisualSafety(candidateWindows, visuallyRiskyScenes);
        Map<String, Object> compressionAudit = auditCompressionReadiness(candidateWindows, targetDuration, requested);

        List<Map<String, Object>> issues = new ArrayList<>();
        collectIssues(issues, distributionAudit);
        collectIssues(issues, scorecardAudit);
        collectIssues(issues, evidenceAudit);
        collectIssues(issues, rankingAudit);
        collectIssues(issues, contextAudit);
        collectIssues(issues, visualAudit);
        collectIssues(issues, compressionAudit);

        List<Map<String, Object>> repairActions = repairActions(
                distributionAudit,
                scorecardAudit,
                evidenceAudit,
                rankingAudit,
                contextAudit,
                visualAudit,
                compressionAudit
        );
        List<String> acceptedWindowIds = acceptedWindowIds(candidateWindows, targetDuration);
        List<String> rejectedWindowIds = rejectedWindowIds(candidateWindows, acceptedWindowIds);
        String status = status(issues, candidateWindows, scoredTranscript);
        double confidence = confidence(status, distributionAudit, scorecardAudit, rankingAudit, contextAudit, compressionAudit);

        Map<String, Object> critic = new LinkedHashMap<>();
        critic.put("status", status);
        critic.put("confidence", round3(confidence));
        critic.put("source", "independent_interestingness_critic_worker");
        critic.put("summary", summary(status, issues, acceptedWindowIds.size(), candidateWindows.size()));
        critic.put("issues", issues);
        critic.put("audits", Map.of(
                "distribution", distributionAudit,
                "scorecards", scorecardAudit,
                "evidence", evidenceAudit,
                "ranking", rankingAudit,
                "contextSafety", contextAudit,
                "visualSafety", visualAudit,
                "compressionReadiness", compressionAudit
        ));
        critic.put("acceptedWindowIds", acceptedWindowIds);
        critic.put("rejectedWindowIds", rejectedWindowIds);
        critic.put("repairActions", repairActions);
        critic.put("scoreThresholds", Map.of(
                "strong", 75,
                "usable", 60,
                "contextOnly", 45,
                "minimumTopWindow", 55
        ));
        critic.put("guidance", guidance(status, videoDna, interestingness, candidateWindows, acceptedWindowIds));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "independent_interestingness_critic_worker");
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("requestedShorts", requested);
        metadata.put("targetDurationSeconds", targetDuration);
        metadata.put("scoredTranscriptNodeCount", scoredTranscript.size());
        metadata.put("topMomentCount", topMoments.size());
        metadata.put("candidateWindowCount", candidateWindows.size());
        metadata.put("storyRiskNodeCount", storyRiskNodes.size());
        metadata.put("visuallyRiskySceneCount", visuallyRiskyScenes.size());
        metadata.put("graphSource", stringValue(graph == null ? null : graph.get("analysisSource"), "unknown"));

        List<Map<String, Object>> trace = List.of(traceRow(
                "INTERESTINGNESS_CRITIC",
                status,
                stringValue(critic.get("summary"), "Independent interestingness critic completed."),
                confidence,
                critic
        ));
        return new InterestingnessCriticResult("PASS".equals(status), status, confidence, critic, trace, metadata);
    }

    private Map<String, Object> auditDistribution(List<Map<String, Object>> scoredTranscript) {
        List<Integer> scores = scoredTranscript.stream()
                .map(node -> intValue(node.get("interestingness"), 0))
                .sorted()
                .toList();
        int top = scores.isEmpty() ? 0 : scores.get(scores.size() - 1);
        int p90 = percentile(scores, 0.90);
        int median = percentile(scores, 0.50);
        int bottom = scores.isEmpty() ? 0 : scores.get(0);
        int spread = top - median;
        long strong = scores.stream().filter(score -> score >= 75).count();
        long usable = scores.stream().filter(score -> score >= 60).count();
        long weak = scores.stream().filter(score -> score < 45).count();
        List<Map<String, Object>> issues = new ArrayList<>();
        if (scoredTranscript.isEmpty()) {
            issues.add(issue("critical", "NO_SCORED_TRANSCRIPT", "No scored transcript nodes exist.", "scoredTranscript"));
        } else {
            if (top < 60) {
                issues.add(issue("high", "NO_STRONG_BEAT", "No strong high-interest beat was found.", "scoredTranscript"));
            }
            if (spread < 10 && scoredTranscript.size() > 5) {
                issues.add(issue("medium", "FLAT_DISTRIBUTION", "Score distribution is too flat for confident ranking.", "scoreDistribution"));
            }
            if (strong == 0 && usable < Math.min(3, scoredTranscript.size())) {
                issues.add(issue("medium", "LOW_USABLE_BEAT_COUNT", "Too few usable beats for requested short generation.", "scoredTranscript"));
            }
        }
        Map<String, Object> audit = auditMap(issues);
        audit.put("nodeCount", scoredTranscript.size());
        audit.put("top", top);
        audit.put("p90", p90);
        audit.put("median", median);
        audit.put("bottom", bottom);
        audit.put("topMedianSpread", spread);
        audit.put("strongNodeCount", strong);
        audit.put("usableNodeCount", usable);
        audit.put("weakNodeCount", weak);
        return audit;
    }

    private Map<String, Object> auditScorecards(List<Map<String, Object>> scoredTranscript, List<Map<String, Object>> topMoments) {
        int missingScorecard = 0;
        int missingDimensions = 0;
        int missingEvidence = 0;
        int mismatch = 0;
        Set<String> topIds = new HashSet<>();
        for (Map<String, Object> moment : topMoments) {
            topIds.add(stringValue(moment.get("nodeId"), ""));
        }
        for (Map<String, Object> node : scoredTranscript) {
            Map<String, Object> scorecard = mutableMap(node.get("interestingnessScorecard"));
            if (scorecard.isEmpty()) {
                missingScorecard++;
                continue;
            }
            if (mutableMap(scorecard.get("dimensions")).isEmpty()) {
                missingDimensions++;
            }
            if (topIds.contains(stringValue(node.get("id"), "")) && listOfStrings(scorecard.get("evidence")).isEmpty()) {
                missingEvidence++;
            }
            int nodeScore = intValue(node.get("interestingness"), -1);
            int cardScore = intValue(scorecard.get("score"), nodeScore);
            if (Math.abs(nodeScore - cardScore) > 2) {
                mismatch++;
            }
        }
        List<Map<String, Object>> issues = new ArrayList<>();
        if (missingScorecard > 0) {
            issues.add(issue("high", "MISSING_SCORECARD", missingScorecard + " nodes are missing interestingness scorecards.", "scorecards"));
        }
        if (missingDimensions > 0) {
            issues.add(issue("medium", "MISSING_DIMENSIONS", missingDimensions + " scorecards are missing dimension breakdowns.", "scorecards"));
        }
        if (missingEvidence > 0) {
            issues.add(issue("medium", "TOP_MOMENT_MISSING_EVIDENCE", missingEvidence + " top moments have no evidence text.", "topMoments"));
        }
        if (mismatch > 0) {
            issues.add(issue("medium", "SCORECARD_SCORE_MISMATCH", mismatch + " scorecards do not match node score.", "scorecards"));
        }
        Map<String, Object> audit = auditMap(issues);
        audit.put("missingScorecardCount", missingScorecard);
        audit.put("missingDimensionCount", missingDimensions);
        audit.put("topMomentMissingEvidenceCount", missingEvidence);
        audit.put("scoreMismatchCount", mismatch);
        return audit;
    }

    private Map<String, Object> auditEvidence(List<Map<String, Object>> topMoments) {
        int evidenceBacked = 0;
        int chainBacked = 0;
        int penalized = 0;
        int frameBacked = 0;
        for (Map<String, Object> moment : topMoments) {
            Map<String, Object> scorecard = mutableMap(moment.get("scorecard"));
            if (!listOfStrings(scorecard.get("evidence")).isEmpty()) {
                evidenceBacked++;
            }
            if (!listOfMaps(scorecard.get("chainEvidence")).isEmpty()) {
                chainBacked++;
            }
            if (!listOfStrings(scorecard.get("penalties")).isEmpty()) {
                penalized++;
            }
            if (listOfStrings(scorecard.get("evidence")).stream().anyMatch(text -> text.toLowerCase(Locale.ROOT).contains("frame"))) {
                frameBacked++;
            }
        }
        List<Map<String, Object>> issues = new ArrayList<>();
        if (!topMoments.isEmpty() && evidenceBacked < Math.ceil(topMoments.size() * 0.70)) {
            issues.add(issue("medium", "WEAK_EVIDENCE_COVERAGE", "Less than 70% of top moments have explicit evidence.", "topMoments"));
        }
        if (!topMoments.isEmpty() && penalized > Math.ceil(topMoments.size() * 0.45)) {
            issues.add(issue("medium", "PENALIZED_TOP_MOMENTS", "Many top moments still carry penalties.", "topMoments"));
        }
        Map<String, Object> audit = auditMap(issues);
        audit.put("topMomentCount", topMoments.size());
        audit.put("evidenceBackedCount", evidenceBacked);
        audit.put("chainBackedCount", chainBacked);
        audit.put("frameBackedCount", frameBacked);
        audit.put("penalizedTopMomentCount", penalized);
        return audit;
    }

    private Map<String, Object> auditRanking(List<Map<String, Object>> windows, int requested) {
        List<Map<String, Object>> issues = new ArrayList<>();
        Set<String> seenNodeSets = new HashSet<>();
        Set<String> anchors = new HashSet<>();
        int duplicates = 0;
        int unsorted = 0;
        int previousScore = Integer.MAX_VALUE;
        int topScore = windows.isEmpty() ? 0 : intValue(windows.get(0).get("score"), 0);
        for (Map<String, Object> window : windows) {
            int score = intValue(window.get("score"), 0);
            if (score > previousScore) {
                unsorted++;
            }
            previousScore = score;
            String nodeKey = String.join(",", listOfStrings(window.get("nodeIds")));
            if (!seenNodeSets.add(nodeKey)) {
                duplicates++;
            }
            String anchor = stringValue(window.get("anchorNodeId"), "");
            if (!anchor.isBlank()) {
                anchors.add(anchor);
            }
        }
        if (windows.isEmpty()) {
            issues.add(issue("critical", "NO_CANDIDATE_WINDOWS", "No ranked interestingness windows exist.", "candidateWindows"));
        }
        if (windows.size() < Math.min(requested, 5)) {
            issues.add(issue("medium", "LOW_WINDOW_COUNT", "Too few candidate windows for requested shorts.", "candidateWindows"));
        }
        if (topScore < 55 && !windows.isEmpty()) {
            issues.add(issue("high", "LOW_TOP_WINDOW_SCORE", "Best interestingness window score is below minimum threshold.", "candidateWindows"));
        }
        if (duplicates > 0) {
            issues.add(issue("medium", "DUPLICATE_WINDOWS", duplicates + " duplicate window node sets were found.", "candidateWindows"));
        }
        if (unsorted > 0) {
            issues.add(issue("medium", "UNSORTED_WINDOWS", "Candidate windows are not sorted by score descending.", "candidateWindows"));
        }
        Map<String, Object> audit = auditMap(issues);
        audit.put("windowCount", windows.size());
        audit.put("requestedShorts", requested);
        audit.put("topWindowScore", topScore);
        audit.put("duplicateWindowCount", duplicates);
        audit.put("unsortedWindowCount", unsorted);
        audit.put("distinctAnchorCount", anchors.size());
        return audit;
    }

    private Map<String, Object> auditContextSafety(List<Map<String, Object>> windows, Set<String> storyRiskNodes) {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<String> isolatedRiskWindows = new ArrayList<>();
        List<String> riskWindows = new ArrayList<>();
        for (Map<String, Object> window : windows) {
            List<String> nodeIds = listOfStrings(window.get("nodeIds"));
            boolean containsRisk = nodeIds.stream().anyMatch(storyRiskNodes::contains);
            if (!containsRisk) {
                continue;
            }
            String windowId = stringValue(window.get("windowId"), "");
            riskWindows.add(windowId);
            if (nodeIds.size() < 2 || listOfMaps(window.get("chainEvidence")).isEmpty()) {
                isolatedRiskWindows.add(windowId);
            }
        }
        if (!isolatedRiskWindows.isEmpty()) {
            issues.add(issue("high", "ISOLATED_CONTEXT_RISK", "Some story-risk nodes appear without enough chain/context evidence.", "candidateWindows"));
        }
        Map<String, Object> audit = auditMap(issues);
        audit.put("storyRiskNodeCount", storyRiskNodes.size());
        audit.put("riskWindowIds", riskWindows);
        audit.put("isolatedRiskWindowIds", isolatedRiskWindows);
        return audit;
    }

    private Map<String, Object> auditVisualSafety(List<Map<String, Object>> windows, Set<String> visuallyRiskyScenes) {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<String> noSceneWindows = new ArrayList<>();
        List<String> riskySceneWindows = new ArrayList<>();
        for (Map<String, Object> window : windows) {
            String windowId = stringValue(window.get("windowId"), "");
            List<String> sceneIds = listOfStrings(window.get("sceneIds"));
            if (sceneIds.isEmpty()) {
                noSceneWindows.add(windowId);
            }
            if (sceneIds.stream().anyMatch(visuallyRiskyScenes::contains)
                    || listOfStrings(window.get("riskFlags")).stream().anyMatch(flag -> flag.toLowerCase(Locale.ROOT).contains("visual"))) {
                riskySceneWindows.add(windowId);
            }
        }
        if (!noSceneWindows.isEmpty()) {
            issues.add(issue("medium", "WINDOW_MISSING_SCENE", "Some candidate windows are not scene-backed.", "candidateWindows"));
        }
        if (!riskySceneWindows.isEmpty()) {
            issues.add(issue("medium", "VISUAL_RISK_WINDOWS", "Some candidate windows include visually risky scenes.", "candidateWindows"));
        }
        Map<String, Object> audit = auditMap(issues);
        audit.put("visuallyRiskySceneCount", visuallyRiskyScenes.size());
        audit.put("missingSceneWindowIds", noSceneWindows);
        audit.put("visualRiskWindowIds", riskySceneWindows);
        return audit;
    }

    private Map<String, Object> auditCompressionReadiness(List<Map<String, Object>> windows, int targetDuration, int requested) {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<String> missingTimestampWindows = new ArrayList<>();
        List<String> missingNodeWindows = new ArrayList<>();
        List<String> overDurationWindows = new ArrayList<>();
        List<String> weakHookPayoffWindows = new ArrayList<>();
        for (Map<String, Object> window : windows) {
            String windowId = stringValue(window.get("windowId"), "");
            if (!hasNumber(window, "start") || !hasNumber(window, "end") || !hasNumber(window, "durationSeconds")) {
                missingTimestampWindows.add(windowId);
            }
            if (listOfStrings(window.get("nodeIds")).isEmpty()) {
                missingNodeWindows.add(windowId);
            }
            if (doubleValue(window.get("durationSeconds"), 0.0) > targetDuration + 1.5) {
                overDurationWindows.add(windowId);
            }
            if (stringValue(window.get("hookCandidate"), "").isBlank() || stringValue(window.get("payoffCandidate"), "").isBlank()) {
                weakHookPayoffWindows.add(windowId);
            }
        }
        if (!missingTimestampWindows.isEmpty()) {
            issues.add(issue("critical", "MISSING_TIMESTAMPS", "Some windows are missing exact timestamps.", "candidateWindows"));
        }
        if (!missingNodeWindows.isEmpty()) {
            issues.add(issue("critical", "MISSING_NODE_IDS", "Some windows are missing transcript node IDs.", "candidateWindows"));
        }
        if (!overDurationWindows.isEmpty()) {
            issues.add(issue("high", "WINDOW_OVER_TARGET", "Some windows exceed target duration.", "candidateWindows"));
        }
        if (weakHookPayoffWindows.size() > Math.max(2, requested / 3)) {
            issues.add(issue("medium", "WEAK_HOOK_PAYOFF_COVERAGE", "Many windows lack clear hook or payoff text.", "candidateWindows"));
        }
        Map<String, Object> audit = auditMap(issues);
        audit.put("missingTimestampWindowIds", missingTimestampWindows);
        audit.put("missingNodeWindowIds", missingNodeWindows);
        audit.put("overDurationWindowIds", overDurationWindows);
        audit.put("weakHookPayoffWindowIds", weakHookPayoffWindows);
        return audit;
    }

    private List<Map<String, Object>> repairActions(Map<String, Object>... audits) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> audit : audits) {
            for (Map<String, Object> issue : listOfMaps(audit.get("issues"))) {
                String code = stringValue(issue.get("code"), "");
                actions.add(switch (code) {
                    case "NO_SCORED_TRANSCRIPT" -> repair("rerun_scoring", "Regenerate interestingness scoring from transcript nodes.", code);
                    case "NO_CANDIDATE_WINDOWS" -> repair("build_windows_from_top_nodes", "Create candidate windows from top scored transcript nodes.", code);
                    case "MISSING_TIMESTAMPS", "MISSING_NODE_IDS" -> repair("drop_invalid_windows", "Remove windows that cannot produce exact timestamp EDLs.", code);
                    case "ISOLATED_CONTEXT_RISK" -> repair("add_chain_context", "Add neighboring chain nodes for context-risk windows.", code);
                    case "VISUAL_RISK_WINDOWS" -> repair("avoid_visual_cut_points", "Prefer windows from visually safe scenes or extend across risky transition.", code);
                    case "FLAT_DISTRIBUTION" -> repair("rerank_with_stronger_dimensions", "Re-rank using hook, payoff, specificity, and chain value dimensions.", code);
                    default -> repair("review_interestingness", stringValue(issue.get("summary"), "Review interestingness scoring output."), code);
                });
            }
        }
        return dedupeActions(actions);
    }

    private List<String> acceptedWindowIds(List<Map<String, Object>> windows, int targetDuration) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> window : windows) {
            int score = intValue(window.get("score"), 0);
            double duration = doubleValue(window.get("durationSeconds"), 0.0);
            if (score >= 55
                    && duration > 0
                    && duration <= targetDuration + 1.5
                    && !listOfStrings(window.get("nodeIds")).isEmpty()
                    && hasNumber(window, "start")
                    && hasNumber(window, "end")) {
                result.add(stringValue(window.get("windowId"), ""));
            }
        }
        return result.stream().filter(id -> !id.isBlank()).toList();
    }

    private List<String> rejectedWindowIds(List<Map<String, Object>> windows, List<String> accepted) {
        Set<String> acceptedSet = new HashSet<>(accepted);
        List<String> result = new ArrayList<>();
        for (Map<String, Object> window : windows) {
            String id = stringValue(window.get("windowId"), "");
            if (!id.isBlank() && !acceptedSet.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private String status(List<Map<String, Object>> issues, List<Map<String, Object>> windows, List<Map<String, Object>> scoredTranscript) {
        if (scoredTranscript.isEmpty() || windows.isEmpty()) {
            return "FAIL";
        }
        boolean critical = issues.stream().anyMatch(issue -> "critical".equalsIgnoreCase(stringValue(issue.get("severity"), "")));
        boolean high = issues.stream().anyMatch(issue -> "high".equalsIgnoreCase(stringValue(issue.get("severity"), "")));
        if (critical) {
            return "FAIL";
        }
        return high || !issues.isEmpty() ? "WARN" : "PASS";
    }

    private double confidence(String status, Map<String, Object> distribution, Map<String, Object> scorecards, Map<String, Object> ranking, Map<String, Object> context, Map<String, Object> compression) {
        double value = "PASS".equals(status) ? 0.72 : "WARN".equals(status) ? 0.56 : 0.32;
        value += Math.min(0.12, intValue(distribution.get("topMedianSpread"), 0) / 250.0);
        value += Math.min(0.08, intValue(ranking.get("windowCount"), 0) / 100.0);
        value -= listOfMaps(scorecards.get("issues")).size() * 0.04;
        value -= listOfMaps(context.get("issues")).size() * 0.05;
        value -= listOfMaps(compression.get("issues")).size() * 0.06;
        return Math.max(0.05, Math.min(0.96, value));
    }

    private String summary(String status, List<Map<String, Object>> issues, int acceptedCount, int totalCount) {
        if ("PASS".equals(status)) {
            return "Independent interestingness critic approved ranked windows for compression.";
        }
        if ("FAIL".equals(status)) {
            return "Independent interestingness critic found blocking issues before compression.";
        }
        return "Independent interestingness critic approved " + acceptedCount + " of " + totalCount + " windows with warnings: "
                + issues.stream().limit(3).map(issue -> stringValue(issue.get("code"), "ISSUE")).toList();
    }

    private Map<String, Object> guidance(String status, Map<String, Object> videoDna, Map<String, Object> interestingness, List<Map<String, Object>> windows, List<String> acceptedWindowIds) {
        Map<String, Object> guidance = new LinkedHashMap<>();
        guidance.put("useForCompression", !"FAIL".equals(status));
        guidance.put("primaryWindowIds", acceptedWindowIds.stream().limit(50).toList());
        guidance.put("fallbackPolicy", "FAIL".equals(status) ? "use_graph_chain_seeds_then_top_scored_nodes" : "use_accepted_windows_then_graph_chain_seeds");
        guidance.put("videoType", stringValue(videoDna == null ? null : videoDna.get("primaryType"), "unknown"));
        guidance.put("topScore", interestingness.getOrDefault("topScore", 0));
        guidance.put("candidateWindowCount", windows.size());
        return guidance;
    }

    private Map<String, Object> auditMap(List<Map<String, Object>> issues) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("status", issues.stream().anyMatch(issue -> "critical".equalsIgnoreCase(stringValue(issue.get("severity"), ""))) ? "FAIL" : issues.isEmpty() ? "PASS" : "WARN");
        audit.put("issues", issues);
        return audit;
    }

    private void collectIssues(List<Map<String, Object>> result, Map<String, Object> audit) {
        result.addAll(listOfMaps(audit == null ? null : audit.get("issues")));
    }

    private Map<String, Object> issue(String severity, String code, String summary, String target) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.put("summary", summary);
        issue.put("target", target);
        return issue;
    }

    private Map<String, Object> repair(String action, String summary, String issueCode) {
        Map<String, Object> repair = new LinkedHashMap<>();
        repair.put("action", action);
        repair.put("summary", summary);
        repair.put("issueCode", issueCode);
        return repair;
    }

    private List<Map<String, Object>> dedupeActions(List<Map<String, Object>> actions) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> action : actions) {
            String key = stringValue(action.get("action"), "") + ":" + stringValue(action.get("issueCode"), "");
            if (seen.add(key)) {
                result.add(action);
            }
        }
        return result;
    }

    private Set<String> storyRiskNodeIds(Map<String, Object> storyCritic) {
        Set<String> result = new HashSet<>();
        for (Map<String, Object> item : listOfMaps(storyCritic == null ? null : storyCritic.get("missingContext"))) {
            result.addAll(listOfStrings(item.get("nodeIds")));
            String nodeId = stringValue(item.get("nodeId"), "");
            if (!nodeId.isBlank()) {
                result.add(nodeId);
            }
        }
        for (Map<String, Object> item : listOfMaps(storyCritic == null ? null : storyCritic.get("brokenChains"))) {
            result.addAll(listOfStrings(item.get("nodeIds")));
        }
        Map<String, Object> guidance = mutableMap(storyCritic == null ? null : storyCritic.get("candidateGuidance"));
        result.addAll(listOfStrings(guidance.get("unsafeHookNodes")));
        result.addAll(listOfStrings(guidance.get("avoidStandaloneNodes")));
        return result;
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

    private int percentile(List<Integer> sortedScores, double percentile) {
        if (sortedScores == null || sortedScores.isEmpty()) {
            return 0;
        }
        int index = Math.max(0, Math.min(sortedScores.size() - 1, (int) Math.round((sortedScores.size() - 1) * percentile)));
        return sortedScores.get(index);
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

    private Map<String, Object> mutableMap(Object value) {
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

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    public record InterestingnessCriticResult(
            boolean passed,
            String status,
            double confidence,
            Map<String, Object> interestingnessCritic,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
