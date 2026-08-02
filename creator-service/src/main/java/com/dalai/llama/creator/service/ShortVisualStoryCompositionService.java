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
public class ShortVisualStoryCompositionService {

    private static final String SOURCE = "deterministic_visual_story_composition_worker";
    private static final double NARRATOR_ANCHOR_SECONDS = 3.5;
    private static final double MAX_FACELESS_RUN_SECONDS = 12.0;

    public VisualStoryCompositionResult compose(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> scenes,
            Map<String, Object> sceneCritic,
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> plans
    ) {
        List<Map<String, Object>> safeCandidates = copyList(candidates);
        List<Map<String, Object>> safePlans = copyList(plans);
        List<Map<String, Object>> safeTranscript = copyList(transcript);
        safeTranscript.sort(Comparator.comparingDouble(node -> doubleValue(node.get("start"), 0.0)));
        Map<String, Map<String, Object>> transcriptById = indexById(safeTranscript);
        Map<String, String> visualRoleByScene = visualRoleByScene(sceneCritic, scenes, videoDna);
        Map<String, Double> speakerConfidenceByScene = speakerConfidenceByScene(sceneCritic, scenes, safeTranscript, videoDna);
        Set<String> narratorSceneIds = roleScenes(visualRoleByScene, Set.of("talking_head", "reaction", "speaker", "host"));
        narratorSceneIds.addAll(confidentSpeakerScenes(speakerConfidenceByScene, 0.55));
        Set<String> supportSceneIds = roleScenes(visualRoleByScene, Set.of("broll", "product", "screen", "slide", "gameplay"));
        if (narratorSceneIds.isEmpty()) {
            narratorSceneIds.addAll(inferredNarratorScenes(videoDna, safeTranscript));
        }

        List<Map<String, Object>> updatedCandidates = new ArrayList<>();
        List<Map<String, Object>> updatedPlans = new ArrayList<>();
        List<Map<String, Object>> audits = new ArrayList<>();
        List<Map<String, Object>> repairs = new ArrayList<>();
        int passCount = 0;
        int warnCount = 0;
        int failCount = 0;
        int count = Math.max(safeCandidates.size(), safePlans.size());
        for (int index = 0; index < count; index++) {
            Map<String, Object> candidate = index < safeCandidates.size() ? new LinkedHashMap<>(safeCandidates.get(index)) : new LinkedHashMap<>();
            Map<String, Object> plan = index < safePlans.size() ? new LinkedHashMap<>(safePlans.get(index)) : new LinkedHashMap<>();
            CompositionOutcome outcome = composeCandidate(
                    video,
                    candidate,
                    plan,
                    safeTranscript,
                    transcriptById,
                    narratorSceneIds,
                    supportSceneIds,
                    visualRoleByScene,
                    speakerConfidenceByScene,
                    index + 1
            );
            updatedCandidates.add(outcome.candidate());
            updatedPlans.add(outcome.plan());
            audits.add(outcome.audit());
            repairs.addAll(outcome.repairs());
            String status = stringValue(outcome.audit().get("status"), "WARN");
            if ("PASS".equals(status)) passCount++;
            else if ("FAIL".equals(status)) failCount++;
            else warnCount++;
        }

        String status = failCount > 0 ? "WARN" : (warnCount > 0 || !repairs.isEmpty() ? "COMPLETED_WITH_REPAIRS" : "COMPLETED");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", SOURCE);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("candidateCount", updatedCandidates.size());
        metadata.put("planCount", updatedPlans.size());
        metadata.put("narratorSceneIds", new ArrayList<>(narratorSceneIds));
        metadata.put("supportSceneIds", new ArrayList<>(supportSceneIds));
        metadata.put("speakerConfidenceByScene", speakerConfidenceByScene);
        metadata.put("passCount", passCount);
        metadata.put("warningCount", warnCount);
        metadata.put("failedCount", failCount);
        metadata.put("repairCount", repairs.size());
        metadata.put("audits", audits);
        metadata.put("repairs", repairs);
        metadata.put("graphSource", stringValue(graph == null ? null : graph.get("analysisSource"), "unknown"));

        List<Map<String, Object>> trace = List.of(traceRow(
                "VISUAL_STORY_COMPOSITION",
                status,
                "Planned narrator anchors, B-roll balance, face/speaker presence, and visual variety for story-beat shorts.",
                failCount > 0 ? 0.68 : repairs.isEmpty() ? 0.9 : 0.82,
                metadata
        ));
        return new VisualStoryCompositionResult(updatedCandidates, updatedPlans, trace, metadata);
    }

    private CompositionOutcome composeCandidate(
            CreatorShortVideo video,
            Map<String, Object> candidate,
            Map<String, Object> plan,
            List<Map<String, Object>> transcript,
            Map<String, Map<String, Object>> transcriptById,
            Set<String> narratorSceneIds,
            Set<String> supportSceneIds,
            Map<String, String> visualRoleByScene,
            Map<String, Double> speakerConfidenceByScene,
            int rank
    ) {
        List<Map<String, Object>> repairs = new ArrayList<>();
        Map<String, Object> edl = mapValue(firstNonEmpty(plan.get("editDecisionList"), candidate.get("editDecisionList")));
        List<Map<String, Object>> segments = listOfMaps(edl.get("segments"));
        int targetDuration = targetDuration(video);
        if (segments.isEmpty()) {
            Map<String, Object> audit = audit(rank, "FAIL", 0, false, false, 0, List.of(issue("high", "NO_SEGMENTS", "No EDL segments exist for visual story composition.")), repairs);
            return new CompositionOutcome(candidate, plan, audit, repairs);
        }

        boolean sourceHasNarrator = !narratorSceneIds.isEmpty();
        boolean candidateHasNarrator = hasSceneRole(segments, narratorSceneIds);
        boolean candidateHasSupport = hasSceneRole(segments, supportSceneIds);
        List<Map<String, Object>> issues = new ArrayList<>();

        if (sourceHasNarrator && !candidateHasNarrator) {
            Map<String, Object> anchor = nearestNarratorSegment(segments, transcript, narratorSceneIds);
            if (!anchor.isEmpty() && canFitAnchor(segments, anchor, targetDuration)) {
                segments.add(0, anchor);
                repairs.add(repair("NARRATOR_ANCHOR_INSERTED", "Inserted nearest narrator/talking-head source segment as the short opening anchor."));
                candidateHasNarrator = true;
            } else {
                issues.add(issue("medium", "NO_NARRATOR_ANCHOR", "Source has narrator/talking-head scenes, but this short has no usable narrator anchor."));
            }
        }

        double facelessRun = longestFacelessRun(segments, narratorSceneIds);
        double speakerCoverageRatio = speakerCoverageRatio(segments, speakerConfidenceByScene);
        int speakerReturnCount = speakerReturnCount(segments, speakerConfidenceByScene);
        if (sourceHasNarrator && speakerCoverageRatio < 0.12 && duration(segments) > 12.0) {
            issues.add(issue("medium", "LOW_SPEAKER_FACE_COVERAGE", "Source has speaker/face evidence, but this short spends too little time on narrator or speaker presence."));
        }
        if (sourceHasNarrator && speakerReturnCount < 2 && duration(segments) > 25.0) {
            issues.add(issue("low", "NO_SPEAKER_RETURN", "Longer short should return to narrator/speaker at least once for viewer trust."));
        }
        if (sourceHasNarrator && facelessRun > MAX_FACELESS_RUN_SECONDS) {
            issues.add(issue("medium", "LONG_FACELESS_RUN", "Short stays away from narrator/talking-head visual for too long."));
        }
        if (!candidateHasSupport && supportSceneIds.size() > 1 && duration(segments) > 25.0) {
            issues.add(issue("low", "LOW_VISUAL_VARIETY", "Source has support/B-roll scenes, but this candidate is visually single-mode."));
        }

        normalizeTimeline(segments, targetDuration);
        edl.put("segments", segments);
        edl.put("actualDurationSeconds", round3(duration(segments)));
        edl.put("timelineEnd", round3(duration(segments)));
        edl.put("visualStoryCompositionSource", SOURCE);

        Map<String, Object> composition = compositionPlan(rank, segments, narratorSceneIds, supportSceneIds, visualRoleByScene, speakerConfidenceByScene, candidateHasNarrator, candidateHasSupport, issues, repairs);
        edl.put("visualStoryComposition", composition);
        plan.put("editDecisionList", edl);
        candidate.put("editDecisionList", edl);

        Map<String, Object> planMetadata = mapValue(plan.get("metadata"));
        planMetadata.put("visualStoryComposition", composition);
        planMetadata.put("visualStoryCompositionSource", SOURCE);
        plan.put("metadata", planMetadata);

        Map<String, Object> metadata = mapValue(candidate.get("metadata"));
        metadata.put("visualStoryComposition", composition);
        metadata.put("visualStoryCompositionSource", SOURCE);
        metadata.put("visualVarietyScore", composition.get("visualVarietyScore"));
        metadata.put("narratorAnchorPresent", candidateHasNarrator);
        candidate.put("metadata", metadata);

        Map<String, Object> renderManifest = mapValue(candidate.get("renderManifest"));
        renderManifest.put("visualStoryComposition", composition);
        renderManifest.put("narratorAnchorRequired", sourceHasNarrator);
        renderManifest.put("narratorAnchorPresent", candidateHasNarrator);
        renderManifest.put("visualCompositionPolicy", "narrator_anchor_broll_balance_v1");
        candidate.put("renderManifest", renderManifest);

        String status = issues.stream().anyMatch(item -> "high".equalsIgnoreCase(stringValue(item.get("severity"), "")))
                ? "FAIL"
                : (issues.isEmpty() && repairs.isEmpty() ? "PASS" : "WARN");
        Map<String, Object> audit = audit(rank, status, intValue(composition.get("visualVarietyScore"), 0), candidateHasNarrator, candidateHasSupport, facelessRun, issues, repairs);
        audit.put("speakerCoverageRatio", round3(speakerCoverageRatio));
        audit.put("speakerReturnCount", speakerReturnCount);
        metadata.put("visualStoryCompositionAudit", audit);
        candidate.put("metadata", metadata);
        return new CompositionOutcome(candidate, plan, audit, repairs);
    }

    private Map<String, Object> compositionPlan(
            int rank,
            List<Map<String, Object>> segments,
            Set<String> narratorSceneIds,
            Set<String> supportSceneIds,
            Map<String, String> visualRoleByScene,
            Map<String, Double> speakerConfidenceByScene,
            boolean narratorPresent,
            boolean supportPresent,
            List<Map<String, Object>> issues,
            List<Map<String, Object>> repairs
    ) {
        List<String> sceneSequence = sceneSequence(segments);
        List<String> roles = sceneSequence.stream().map(sceneId -> visualRoleByScene.getOrDefault(sceneId, "unknown")).toList();
        int varietyScore = visualVarietyScore(sceneSequence, roles, narratorPresent, supportPresent, issues);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("source", SOURCE);
        plan.put("rankIndex", rank);
        plan.put("policy", "narrator_anchor_broll_balance_v1");
        plan.put("narratorSceneIds", new ArrayList<>(narratorSceneIds));
        plan.put("supportSceneIds", new ArrayList<>(supportSceneIds));
        plan.put("sceneSequence", sceneSequence);
        plan.put("visualRoleSequence", roles);
        plan.put("speakerConfidenceSequence", sceneSequence.stream().map(sceneId -> round3(speakerConfidenceByScene.getOrDefault(sceneId, 0.0))).toList());
        plan.put("speakerCoverageRatio", round3(speakerCoverageRatio(segments, speakerConfidenceByScene)));
        plan.put("speakerReturnCount", speakerReturnCount(segments, speakerConfidenceByScene));
        plan.put("narratorAnchorPresent", narratorPresent);
        plan.put("supportVisualPresent", supportPresent);
        plan.put("visualVarietyScore", varietyScore);
        plan.put("longestFacelessRunSeconds", round3(longestFacelessRun(segments, narratorSceneIds)));
        plan.put("compositionRules", List.of(
                "prefer_narrator_or_speaker_anchor_when_available",
                "use_broll_screen_or_product_scenes_as_supporting_evidence",
                "avoid_long_faceless_runs_unless_screen_or_product_is_the_content",
                "preserve_original_story_node_order"
        ));
        plan.put("issues", issues);
        plan.put("repairs", repairs);
        plan.put("generatedAt", OffsetDateTime.now().toString());
        return plan;
    }

    private Map<String, String> visualRoleByScene(Map<String, Object> sceneCritic, List<Map<String, Object>> scenes, Map<String, Object> videoDna) {
        Map<String, String> roles = new LinkedHashMap<>();
        for (Map<String, Object> audit : sceneAudits(sceneCritic)) {
            String sceneId = stringValue(audit.get("sceneId"), "");
            String role = normalizeRole(stringValue(firstNonEmpty(audit.get("visualRole"), audit.get("subjects")), ""));
            if (!sceneId.isBlank() && !role.isBlank()) {
                roles.put(sceneId, role);
            }
        }
        for (Map<String, Object> scene : copyList(scenes)) {
            String sceneId = stringValue(scene.get("id"), "");
            if (!sceneId.isBlank()) {
                roles.putIfAbsent(sceneId, inferRoleFromText(scene.toString(), videoDna));
            }
        }
        return roles;
    }

    private Map<String, Double> speakerConfidenceByScene(Map<String, Object> sceneCritic, List<Map<String, Object>> scenes, List<Map<String, Object>> transcript, Map<String, Object> videoDna) {
        Map<String, Double> confidence = new LinkedHashMap<>();
        for (Map<String, Object> audit : sceneAudits(sceneCritic)) {
            String sceneId = stringValue(audit.get("sceneId"), "");
            if (sceneId.isBlank()) continue;
            String signal = (audit.toString() + " " + stringValue(audit.get("visualRole"), "") + " " + stringValue(audit.get("subjects"), "")).toLowerCase(Locale.ROOT);
            double score = 0.0;
            if (containsAny(signal, "talking_head", "speaker", "host", "narrator", "face", "person")) score = Math.max(score, 0.85);
            if (containsAny(signal, "reaction", "interview", "podcast")) score = Math.max(score, 0.72);
            if (containsAny(signal, "screen", "slide", "product", "gameplay")) score = Math.max(score, 0.18);
            if (score > 0.0) confidence.put(sceneId, score);
        }
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            String sceneId = stringValue(node.get("sceneId"), "");
            if (!sceneId.isBlank() && !stringValue(node.get("speaker"), "").isBlank()) {
                confidence.merge(sceneId, 0.62, Math::max);
            }
        }
        for (Map<String, Object> scene : copyList(scenes)) {
            String sceneId = stringValue(scene.get("id"), "");
            if (!sceneId.isBlank() && containsAny(scene.toString(), "speaker", "face", "talking", "host")) {
                confidence.merge(sceneId, 0.58, Math::max);
            }
        }
        String type = (videoDna == null ? "" : videoDna.toString()).toLowerCase(Locale.ROOT);
        if (containsAny(type, "podcast", "interview", "conversation", "talking")) {
            for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
                String sceneId = stringValue(node.get("sceneId"), "");
                if (!sceneId.isBlank()) confidence.merge(sceneId, 0.52, Math::max);
            }
        }
        return confidence;
    }

    private Set<String> confidentSpeakerScenes(Map<String, Double> confidence, double threshold) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, Double> entry : confidence.entrySet()) {
            if (entry.getValue() != null && entry.getValue() >= threshold) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private double speakerCoverageRatio(List<Map<String, Object>> segments, Map<String, Double> confidenceByScene) {
        double total = 0.0;
        double speaker = 0.0;
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            double duration = Math.max(0.0, doubleValue(segment.get("durationSeconds"), doubleValue(segment.get("sourceEnd"), 0.0) - doubleValue(segment.get("sourceStart"), 0.0)));
            total += duration;
            double confidence = confidenceByScene.getOrDefault(stringValue(segment.get("sceneId"), ""), 0.0);
            speaker += duration * confidence;
        }
        return total <= 0.0 ? 0.0 : speaker / total;
    }

    private int speakerReturnCount(List<Map<String, Object>> segments, Map<String, Double> confidenceByScene) {
        int count = 0;
        boolean inSpeaker = false;
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            boolean speaker = confidenceByScene.getOrDefault(stringValue(segment.get("sceneId"), ""), 0.0) >= 0.55;
            if (speaker && !inSpeaker) count++;
            inSpeaker = speaker;
        }
        return count;
    }

    private List<Map<String, Object>> sceneAudits(Map<String, Object> sceneCritic) {
        List<Map<String, Object>> direct = listOfMaps(sceneCritic == null ? null : sceneCritic.get("sceneAudits"));
        if (!direct.isEmpty()) {
            return direct;
        }
        return listOfMaps(mapValue(sceneCritic == null ? null : sceneCritic.get("critic")).get("sceneAudits"));
    }

    private Set<String> roleScenes(Map<String, String> roles, Set<String> wanted) {
        Set<String> ids = new HashSet<>();
        for (Map.Entry<String, String> entry : roles.entrySet()) {
            if (wanted.contains(entry.getValue())) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    private Set<String> inferredNarratorScenes(Map<String, Object> videoDna, List<Map<String, Object>> transcript) {
        Set<String> ids = new HashSet<>();
        String type = (stringValue(videoDna == null ? null : videoDna.get("primaryType"), "") + " "
                + stringValue(videoDna == null ? null : videoDna.get("structureType"), "")).toLowerCase(Locale.ROOT);
        if (!containsAny(type, "podcast", "interview", "tutorial", "conversation", "vlog", "presentation")) {
            return ids;
        }
        for (Map<String, Object> node : transcript) {
            String sceneId = stringValue(node.get("sceneId"), "");
            if (!sceneId.isBlank() && !stringValue(node.get("speaker"), "").isBlank()) {
                ids.add(sceneId);
            }
        }
        return ids;
    }

    private Map<String, Object> nearestNarratorSegment(List<Map<String, Object>> segments, List<Map<String, Object>> transcript, Set<String> narratorSceneIds) {
        double anchorStart = segments.stream().mapToDouble(segment -> doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0))).min().orElse(0.0);
        return transcript.stream()
                .filter(node -> narratorSceneIds.contains(stringValue(node.get("sceneId"), "")))
                .min(Comparator.comparingDouble(node -> Math.abs(doubleValue(node.get("start"), 0.0) - anchorStart)))
                .map(node -> narratorSegment(node, "Opening narrator/speaker anchor for viewer trust."))
                .orElseGet(LinkedHashMap::new);
    }

    private Map<String, Object> narratorSegment(Map<String, Object> node, String reason) {
        double start = doubleValue(node.get("start"), 0.0);
        double end = Math.max(start + 0.4, Math.min(doubleValue(node.get("end"), start + NARRATOR_ANCHOR_SECONDS), start + NARRATOR_ANCHOR_SECONDS));
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("nodeId", stringValue(node.get("id"), ""));
        segment.put("sceneId", stringValue(node.get("sceneId"), ""));
        segment.put("sourceStart", round3(start));
        segment.put("sourceEnd", round3(end));
        segment.put("durationSeconds", round3(end - start));
        segment.put("operation", "KEEP_NARRATOR_ANCHOR");
        segment.put("chainType", "VISUAL_STORY_COMPOSITION");
        segment.put("beatRole", "NARRATOR_ANCHOR");
        segment.put("reason", reason);
        segment.put("transcript", stringValue(node.get("transcript"), ""));
        segment.put("frames", node.getOrDefault("frames", List.of()));
        segment.put("insertedBy", SOURCE);
        return segment;
    }

    private boolean canFitAnchor(List<Map<String, Object>> segments, Map<String, Object> anchor, int targetDuration) {
        if (anchor.isEmpty()) {
            return false;
        }
        return duration(segments) + doubleValue(anchor.get("durationSeconds"), NARRATOR_ANCHOR_SECONDS) <= targetDuration + 2.0;
    }

    private void normalizeTimeline(List<Map<String, Object>> segments, int targetDuration) {
        segments.sort(Comparator.comparingDouble(segment -> doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0))));
        double cursor = 0.0;
        for (Map<String, Object> segment : segments) {
            double sourceStart = Math.max(0.0, doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0)));
            double sourceEnd = Math.max(sourceStart + 0.1, doubleValue(segment.get("sourceEnd"), doubleValue(segment.get("end"), sourceStart + 0.1)));
            double remaining = Math.max(0.0, targetDuration - cursor);
            if (sourceEnd - sourceStart > remaining && remaining >= 1.5 && !"KEEP_NARRATOR_ANCHOR".equals(segment.get("operation"))) {
                sourceEnd = sourceStart + remaining;
            }
            double duration = Math.max(0.1, sourceEnd - sourceStart);
            segment.put("sourceStart", round3(sourceStart));
            segment.put("sourceEnd", round3(sourceEnd));
            segment.put("timelineStart", round3(cursor));
            segment.put("timelineEnd", round3(cursor + duration));
            segment.put("durationSeconds", round3(duration));
            cursor += duration;
        }
    }

    private boolean hasSceneRole(List<Map<String, Object>> segments, Set<String> sceneIds) {
        if (sceneIds.isEmpty()) {
            return false;
        }
        for (Map<String, Object> segment : segments) {
            if (sceneIds.contains(stringValue(segment.get("sceneId"), ""))) {
                return true;
            }
        }
        return false;
    }

    private double longestFacelessRun(List<Map<String, Object>> segments, Set<String> narratorSceneIds) {
        double current = 0.0;
        double longest = 0.0;
        for (Map<String, Object> segment : segments) {
            double duration = doubleValue(segment.get("durationSeconds"), doubleValue(segment.get("sourceEnd"), 0.0) - doubleValue(segment.get("sourceStart"), 0.0));
            if (narratorSceneIds.contains(stringValue(segment.get("sceneId"), ""))) {
                current = 0.0;
            } else {
                current += Math.max(0.0, duration);
                longest = Math.max(longest, current);
            }
        }
        return longest;
    }

    private List<String> sceneSequence(List<Map<String, Object>> segments) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> segment : segments) {
            String sceneId = stringValue(segment.get("sceneId"), "");
            if (!sceneId.isBlank() && (ids.isEmpty() || !sceneId.equals(ids.get(ids.size() - 1)))) {
                ids.add(sceneId);
            }
        }
        return ids;
    }

    private int visualVarietyScore(List<String> sceneSequence, List<String> roles, boolean narratorPresent, boolean supportPresent, List<Map<String, Object>> issues) {
        int score = 48;
        score += Math.min(20, Math.max(0, sceneSequence.size() - 1) * 6);
        if (narratorPresent) score += 18;
        if (supportPresent) score += 14;
        if (new HashSet<>(roles).size() > 1) score += 10;
        for (Map<String, Object> issue : issues) {
            String severity = stringValue(issue.get("severity"), "");
            if ("high".equals(severity)) score -= 25;
            else if ("medium".equals(severity)) score -= 12;
            else score -= 5;
        }
        return Math.max(0, Math.min(100, score));
    }

    private Map<String, Object> audit(int rank, String status, int varietyScore, boolean narratorPresent, boolean supportPresent, double facelessRun, List<Map<String, Object>> issues, List<Map<String, Object>> repairs) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("source", SOURCE);
        audit.put("status", status);
        audit.put("passed", !"FAIL".equals(status));
        audit.put("candidateRank", rank);
        audit.put("visualVarietyScore", varietyScore);
        audit.put("narratorAnchorPresent", narratorPresent);
        audit.put("supportVisualPresent", supportPresent);
        audit.put("longestFacelessRunSeconds", round3(facelessRun));
        audit.put("issueCount", issues.size());
        audit.put("repairCount", repairs.size());
        audit.put("issues", issues);
        audit.put("repairs", repairs);
        audit.put("timestamp", OffsetDateTime.now().toString());
        return audit;
    }

    private Map<String, Object> issue(String severity, String code, String summary) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.put("summary", summary);
        return issue;
    }

    private Map<String, Object> repair(String code, String summary) {
        Map<String, Object> repair = new LinkedHashMap<>();
        repair.put("source", SOURCE);
        repair.put("code", code);
        repair.put("summary", summary);
        repair.put("timestamp", OffsetDateTime.now().toString());
        return repair;
    }

    private String normalizeRole(String role) {
        String normalized = stringValue(role, "").toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.contains("talking") || normalized.contains("speaker") || normalized.contains("host") || normalized.contains("face")) return "talking_head";
        if (normalized.contains("reaction")) return "reaction";
        if (normalized.contains("screen")) return "screen";
        if (normalized.contains("slide")) return "slide";
        if (normalized.contains("product")) return "product";
        if (normalized.contains("game")) return "gameplay";
        if (normalized.contains("broll") || normalized.contains("b_roll") || normalized.contains("visual")) return "broll";
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private String inferRoleFromText(String text, Map<String, Object> videoDna) {
        String signal = (stringValue(text, "") + " " + (videoDna == null ? "" : videoDna.toString())).toLowerCase(Locale.ROOT);
        if (containsAny(signal, "talking", "speaker", "host", "interview", "podcast", "face")) return "talking_head";
        if (containsAny(signal, "screen", "dashboard", "code", "screencast")) return "screen";
        if (containsAny(signal, "slide", "presentation")) return "slide";
        if (containsAny(signal, "product", "demo")) return "product";
        if (containsAny(signal, "gameplay", "gaming")) return "gameplay";
        return "unknown";
    }

    private boolean containsAny(String value, String... needles) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (normalized.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private Object firstNonEmpty(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value instanceof Map<?, ?> map && !map.isEmpty()) return value;
            if (value instanceof List<?> list && !list.isEmpty()) return value;
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
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

    private int targetDuration(CreatorShortVideo video) {
        return Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());
    }

    private double duration(List<Map<String, Object>> segments) {
        double total = 0.0;
        for (Map<String, Object> segment : segments) {
            total += Math.max(0.0, doubleValue(segment.get("durationSeconds"), doubleValue(segment.get("sourceEnd"), 0.0) - doubleValue(segment.get("sourceStart"), 0.0)));
        }
        return total;
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
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> {
                if (key != null) normalized.put(String.valueOf(key), mapValue);
            });
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
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

    private record CompositionOutcome(
            Map<String, Object> candidate,
            Map<String, Object> plan,
            Map<String, Object> audit,
            List<Map<String, Object>> repairs
    ) {
    }

    public record VisualStoryCompositionResult(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> plans,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
