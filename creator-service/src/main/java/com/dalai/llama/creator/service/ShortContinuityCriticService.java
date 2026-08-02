package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ShortContinuityCriticService {

    private static final Logger log = LoggerFactory.getLogger(ShortContinuityCriticService.class);
    private static final String SOURCE = "deterministic_continuity_critic_worker";
    private static final double TIMING_EPSILON = 0.05;
    private static final double MIN_SEGMENT_SECONDS = 0.35;
    private static final double MIN_CAPTION_SECONDS = 0.5;
    private static final int TARGET_CAPTION_CHARS = 56;
    private static final int MAX_CAPTION_CHARS = 72;

    public ContinuityCriticResult critique(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> candidatePayloads,
            List<Map<String, Object>> scenes,
            Map<String, Object> storyCritic,
            Map<String, Object> sceneCritic
    ) {
        List<Map<String, Object>> candidates = copyList(candidatePayloads);
        List<Map<String, Object>> safeTranscript = copyList(transcript);
        List<Map<String, Object>> safeScenes = copyList(scenes);
        if (candidates.isEmpty()) {
            List<Map<String, Object>> trace = List.of(traceRow(
                    "CONTINUITY_CRITIC",
                    "WARN",
                    "Continuity critic could not run because no visual-verified candidates were available.",
                    0.25,
                    Map.of("source", SOURCE)
            ));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", SOURCE);
            metadata.put("generatedAt", OffsetDateTime.now().toString());
            metadata.put("candidateCount", 0);
            metadata.put("passedCount", 0);
            metadata.put("repairCount", 0);
            metadata.put("failedCount", 0);
            return new ContinuityCriticResult(candidates, trace, metadata);
        }

        Evidence evidence = evidence(safeTranscript, safeScenes, graph);
        List<Map<String, Object>> repairedCandidates = new ArrayList<>();
        List<Map<String, Object>> audits = new ArrayList<>();
        List<Map<String, Object>> allRepairs = new ArrayList<>();
        int passedCount = 0;
        int warningCount = 0;
        int failedCount = 0;

        for (int index = 0; index < candidates.size(); index++) {
            AuditOutcome outcome = auditCandidate(
                    video,
                    videoDna,
                    candidates.get(index),
                    evidence,
                    storyCritic,
                    sceneCritic,
                    index + 1
            );
            repairedCandidates.add(outcome.candidate());
            audits.add(outcome.audit());
            allRepairs.addAll(outcome.repairs());
            String status = stringValue(outcome.audit().get("status"), "WARN");
            if ("PASS".equals(status)) {
                passedCount++;
            } else if ("FAIL".equals(status)) {
                failedCount++;
            } else {
                warningCount++;
            }
        }

        String status = failedCount > 0
                ? "FAILED"
                : (!allRepairs.isEmpty() || warningCount > 0 ? "COMPLETED_WITH_REPAIRS" : "COMPLETED");
        double confidence = failedCount > 0 ? 0.5 : allRepairs.isEmpty() && warningCount == 0 ? 0.93 : 0.82;

        Map<String, Object> traceMetadata = new LinkedHashMap<>();
        traceMetadata.put("source", SOURCE);
        traceMetadata.put("candidateCount", repairedCandidates.size());
        traceMetadata.put("passedCount", passedCount);
        traceMetadata.put("warningCount", warningCount);
        traceMetadata.put("failedCount", failedCount);
        traceMetadata.put("repairCount", allRepairs.size());
        traceMetadata.put("transcriptNodeCount", safeTranscript.size());
        traceMetadata.put("sceneCount", safeScenes.size());
        traceMetadata.put("graphProtectedChainCount", evidence.protectedChains().size());
        traceMetadata.put("graphEdgeCount", evidence.edges().size());

        List<Map<String, Object>> trace = List.of(traceRow(
                "CONTINUITY_CRITIC",
                status,
                failedCount > 0
                        ? "Continuity critic found unresolved timeline, story-chain, or render-alignment issues that need review."
                        : allRepairs.isEmpty()
                        ? "Continuity critic verified exact timeline order, protected story chains, scene continuity, captions, and render segment alignment."
                        : "Continuity critic verified and repaired bounded timeline, caption, scene, and render-manifest continuity issues.",
                confidence,
                traceMetadata
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", SOURCE);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("candidateCount", repairedCandidates.size());
        metadata.put("passedCount", passedCount);
        metadata.put("warningCount", warningCount);
        metadata.put("failedCount", failedCount);
        metadata.put("repairCount", allRepairs.size());
        metadata.put("audits", audits);
        metadata.put("repairs", allRepairs);

        return new ContinuityCriticResult(repairedCandidates, trace, metadata);
    }

    private AuditOutcome auditCandidate(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            Map<String, Object> candidatePayload,
            Evidence evidence,
            Map<String, Object> storyCritic,
            Map<String, Object> sceneCritic,
            int rank
    ) {
        Map<String, Object> candidate = new LinkedHashMap<>(candidatePayload);
        Map<String, Object> edl = mapValue(candidate.get("editDecisionList"));
        Map<String, Object> captionPlan = mapValue(candidate.get("captionPlan"));
        Map<String, Object> renderManifest = mapValue(candidate.get("renderManifest"));
        Map<String, Object> metadata = mapValue(candidate.get("metadata"));
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> repairs = new ArrayList<>();

        List<Map<String, Object>> rawSegments = listOfMaps(edl.get("segments"));
        if (rawSegments.isEmpty()) {
            rawSegments = fallbackSegments(video, evidence.transcript(), rank);
            addIssue(issues, repairs, "high", "EDL_SEGMENTS_MISSING", "Rebuilt minimal EDL segments from transcript evidence.", true);
        }
        List<Map<String, Object>> segments = normalizeSegments(rawSegments, evidence, issues, repairs);
        if (segments.isEmpty()) {
            addIssue(issues, repairs, "high", "NO_RENDERABLE_SEGMENTS", "No renderable segments remain after continuity normalization.", false);
        }

        double actualDuration = durationFromSegments(segments);
        int targetDuration = targetDuration(video);
        if (actualDuration <= 0.0) {
            addIssue(issues, repairs, "high", "ZERO_DURATION", "Candidate duration is zero after continuity normalization.", false);
        } else if (actualDuration < Math.min(5.0, targetDuration * 0.35)) {
            addIssue(issues, repairs, "medium", "SHORT_DURATION", "Candidate is much shorter than requested target duration.", false);
        } else if (actualDuration > targetDuration + 2.0) {
            addIssue(issues, repairs, "medium", "LONG_DURATION", "Candidate exceeds target duration by more than two seconds.", false);
        }

        evaluateSourceOrder(segments, edl, videoDna, issues, repairs);
        evaluateGraphContinuity(segments, edl, evidence, storyCritic, issues, repairs);
        evaluateSceneContinuity(segments, evidence, sceneCritic, issues, repairs);
        captionPlan = repairCaptions(video, captionPlan, segments, actualDuration, issues, repairs);
        renderManifest = repairRenderManifest(video, renderManifest, edl, segments, actualDuration, issues, repairs);
        repairVisualPlan(renderManifest, segments, repairs);

        edl.put("segments", segments);
        edl.put("version", intValue(edl.get("version"), 2));
        edl.put("source", stringValue(edl.get("source"), "deterministic_compression_worker"));
        edl.put("exactTimestampEdl", true);
        edl.put("actualDurationSeconds", round3(actualDuration));
        edl.put("timelineStart", 0);
        edl.put("timelineEnd", round3(actualDuration));
        edl.put("renderPolicy", "concat_source_segments_in_timeline_order");
        ensureProtectedChains(edl, segments, evidence, repairs);

        String status = noUnresolvedHigh(issues)
                ? (issues.isEmpty() && repairs.isEmpty() ? "PASS" : "WARN")
                : "FAIL";
        double confidence = "PASS".equals(status) ? 0.93 : ("WARN".equals(status) ? 0.78 : 0.44);

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("source", SOURCE);
        audit.put("status", status);
        audit.put("passed", !"FAIL".equals(status));
        audit.put("confidence", confidence);
        audit.put("candidateRank", rank);
        audit.put("candidateTitle", stringValue(candidate.get("title"), ""));
        audit.put("durationSeconds", round3(actualDuration));
        audit.put("targetDurationSeconds", targetDuration);
        audit.put("segmentCount", segments.size());
        audit.put("protectedChainCount", listOfMaps(edl.get("protectedChains")).size());
        audit.put("issueCount", issues.size());
        audit.put("repairCount", repairs.size());
        audit.put("issues", issues);
        audit.put("repairs", repairs);
        audit.put("checkedDimensions", List.of(
                "timeline_contiguity",
                "source_order",
                "protected_story_chains",
                "question_answer_and_setup_payoff_edges",
                "scene_transition_safety",
                "caption_timing_continuity",
                "render_manifest_alignment",
                "visual_segment_plan_alignment",
                "audio_cut_risk"
        ));
        audit.put("timestamp", OffsetDateTime.now().toString());

        renderManifest.put("continuityCritic", audit);
        renderManifest.put("continuityCriticStatus", status);
        renderManifest.put("continuityRenderRisk", "FAIL".equals(status) ? "HIGH" : ("WARN".equals(status) ? "MEDIUM" : "LOW"));
        renderManifest.put("requiresHumanReview", Boolean.TRUE.equals(renderManifest.get("requiresHumanReview")) || "FAIL".equals(status));

        Map<String, Object> critics = mapValue(metadata.get("critics"));
        critics.put("productionContinuityCritic", audit);
        metadata.put("critics", critics);
        metadata.put("continuityCritic", audit);
        metadata.put("continuityCriticStatus", status);
        metadata.put("continuityCriticSource", SOURCE);
        metadata.put("continuityRepairCount", repairs.size());
        metadata.put("continuityIssueCount", issues.size());
        metadata.put("repairStatus", repairs.isEmpty() ? stringValue(metadata.get("repairStatus"), "NO_REPAIR_NEEDED") : "REPAIRED");

        candidate.put("editDecisionList", edl);
        candidate.put("captionPlan", captionPlan);
        candidate.put("renderManifest", renderManifest);
        candidate.put("durationSeconds", Math.max(1, (int) Math.round(actualDuration)));
        candidate.put("metadata", metadata);

        return new AuditOutcome(candidate, audit, repairs);
    }

    private List<Map<String, Object>> normalizeSegments(
            List<Map<String, Object>> rawSegments,
            Evidence evidence,
            List<Map<String, Object>> issues,
            List<Map<String, Object>> repairs
    ) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        double cursor = 0.0;
        Set<String> seenFingerprints = new LinkedHashSet<>();
        for (int index = 0; index < rawSegments.size(); index++) {
            Map<String, Object> original = rawSegments.get(index);
            Map<String, Object> segment = new LinkedHashMap<>(original);
            String nodeId = stringValue(segment.get("nodeId"), "");
            Map<String, Object> node = nodeId.isBlank() ? nodeAt(evidence.transcript(), doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0))) : evidence.nodesById().getOrDefault(nodeId, Map.of());
            if (nodeId.isBlank() && !node.isEmpty()) {
                nodeId = stringValue(node.get("id"), "");
                putIfChanged(segment, "nodeId", nodeId, repairs, "SEGMENT_NODE_ID_REPAIRED", "Inferred missing segment node id from transcript timing.");
            }

            double sourceStart = Math.max(0.0, doubleValue(firstNonEmpty(segment.get("sourceStart"), segment.get("start"), node.get("start")), 0.0));
            double nodeEnd = doubleValue(node.get("end"), sourceStart + 1.0);
            double sourceEnd = doubleValue(firstNonEmpty(segment.get("sourceEnd"), segment.get("end"), node.get("end")), sourceStart + 1.0);
            if (sourceEnd <= sourceStart + TIMING_EPSILON) {
                sourceEnd = Math.max(sourceStart + MIN_SEGMENT_SECONDS, nodeEnd);
                addIssue(issues, repairs, "medium", "SEGMENT_DURATION_REPAIRED", "Repaired invalid segment duration.", true);
            }
            if (sourceEnd - sourceStart < MIN_SEGMENT_SECONDS) {
                sourceEnd = sourceStart + MIN_SEGMENT_SECONDS;
                addIssue(issues, repairs, "medium", "MICRO_SEGMENT_EXTENDED", "Extended sub-frame segment to minimum renderable duration.", true);
            }

            double duration = sourceEnd - sourceStart;
            String sceneId = stringValue(firstNonEmpty(segment.get("sceneId"), node.get("sceneId")), "");
            if (sceneId.isBlank()) {
                sceneId = sceneIdAt(evidence.scenes(), midpoint(sourceStart, sourceEnd));
                if (!sceneId.isBlank()) {
                    addRepair(repairs, "SEGMENT_SCENE_ID_REPAIRED", "Inferred missing scene id from segment timing.");
                }
            }

            String transcriptText = stringValue(firstNonEmpty(segment.get("transcript"), node.get("transcript"), node.get("text")), "");
            String fingerprint = "%s|%.2f|%.2f".formatted(nodeId, sourceStart, sourceEnd);
            if (!seenFingerprints.add(fingerprint)) {
                addIssue(issues, repairs, "medium", "DUPLICATE_SEGMENT", "Duplicate EDL segment detected.", false);
            }

            putIfChanged(segment, "sourceStart", round3(sourceStart), repairs, "SEGMENT_SOURCE_START_REPAIRED", "Normalized segment source start.");
            putIfChanged(segment, "sourceEnd", round3(sourceEnd), repairs, "SEGMENT_SOURCE_END_REPAIRED", "Normalized segment source end.");
            putIfChanged(segment, "timelineStart", round3(cursor), repairs, "SEGMENT_TIMELINE_START_REPAIRED", "Rebuilt contiguous segment timeline start.");
            putIfChanged(segment, "timelineEnd", round3(cursor + duration), repairs, "SEGMENT_TIMELINE_END_REPAIRED", "Rebuilt contiguous segment timeline end.");
            putIfChanged(segment, "durationSeconds", round3(duration), repairs, "SEGMENT_DURATION_REPAIRED", "Recomputed segment duration.");
            putIfChanged(segment, "sceneId", sceneId, repairs, "SEGMENT_SCENE_ID_REPAIRED", "Aligned segment scene id.");
            if (!transcriptText.isBlank()) {
                segment.put("transcript", transcriptText);
            }
            applySpeakerFocus(segment, node, evidence);
            segment.putIfAbsent("operation", "KEEP");
            segment.putIfAbsent("reason", "Kept by compression and continuity workers.");

            if (audioCutRisk(segment, evidence)) {
                addIssue(issues, repairs, "medium", "AUDIO_CUT_RISK", "Segment appears to cut through an unfinished spoken thought.", false);
            }

            normalized.add(segment);
            cursor += duration;
        }
        return normalized;
    }

    private void evaluateSourceOrder(
            List<Map<String, Object>> segments,
            Map<String, Object> edl,
            Map<String, Object> videoDna,
            List<Map<String, Object>> issues,
            List<Map<String, Object>> repairs
    ) {
        boolean allowReorder = Boolean.TRUE.equals(edl.get("allowSourceReorder"))
                || containsAny(stringValue(edl.get("strategy"), ""), "HOOK", "REVEAL")
                || containsAny(stringValue(videoDna == null ? null : videoDna.get("structureType"), ""), "reveal", "reaction");
        double previousSourceStart = -1.0;
        int backwards = 0;
        for (Map<String, Object> segment : segments) {
            double sourceStart = doubleValue(segment.get("sourceStart"), -1.0);
            if (previousSourceStart >= 0 && sourceStart < previousSourceStart - 0.25) {
                backwards++;
            }
            previousSourceStart = sourceStart;
        }
        if (backwards > 0 && !allowReorder) {
            addIssue(issues, repairs, "high", "SOURCE_ORDER_BACKTRACK", "EDL source order moves backward without an explicit reorder strategy.", false);
        } else if (backwards > 1) {
            addIssue(issues, repairs, "medium", "SOURCE_ORDER_REORDERED", "EDL uses multiple source-order jumps; verify narrative intent.", false);
        }
    }

    private void evaluateGraphContinuity(
            List<Map<String, Object>> segments,
            Map<String, Object> edl,
            Evidence evidence,
            Map<String, Object> storyCritic,
            List<Map<String, Object>> issues,
            List<Map<String, Object>> repairs
    ) {
        List<String> segmentNodeIds = segmentNodeIds(segments);
        Map<String, Integer> order = orderMap(segmentNodeIds);
        for (Map<String, Object> edge : evidence.edges()) {
            String type = stringValue(edge.get("type"), "").toUpperCase(Locale.ROOT);
            if (!isProtectedEdge(type)) {
                continue;
            }
            String from = stringValue(firstNonEmpty(edge.get("from"), edge.get("source"), edge.get("sourceId")), "");
            String to = stringValue(firstNonEmpty(edge.get("to"), edge.get("target"), edge.get("targetId")), "");
            boolean hasFrom = order.containsKey(from);
            boolean hasTo = order.containsKey(to);
            if (hasTo && !hasFrom) {
                addIssue(issues, repairs, "medium", "MISSING_CONTEXT_NODE", "Candidate includes payoff/answer without its setup/context node.", false);
            }
            if (hasFrom && hasTo && order.get(from) > order.get(to)) {
                addIssue(issues, repairs, "high", "PROTECTED_EDGE_ORDER_BROKEN", "Protected story edge order is reversed in the EDL.", false);
            }
        }

        for (Map<String, Object> chain : listOfMaps(edl.get("protectedChains"))) {
            List<String> nodes = listOfStrings(firstNonEmpty(chain.get("nodeIds"), chain.get("nodes")));
            if (nodes.size() < 2) {
                continue;
            }
            int previous = -1;
            for (String nodeId : nodes) {
                Integer current = order.get(nodeId);
                if (current == null) {
                    addIssue(issues, repairs, "medium", "PROTECTED_CHAIN_NODE_MISSING", "Protected chain references a node missing from the candidate EDL.", false);
                    continue;
                }
                if (previous > current) {
                    addIssue(issues, repairs, "high", "PROTECTED_CHAIN_ORDER_BROKEN", "Protected chain node order is broken.", false);
                    break;
                }
                previous = current;
            }
        }

        if (listOfMaps(edl.get("protectedChains")).isEmpty() && !segmentNodeIds.isEmpty()) {
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("type", inferredChainType(edl, storyCritic));
            chain.put("nodeIds", segmentNodeIds);
            chain.put("reason", "Continuity critic rebuilt protected chain from final EDL node order.");
            edl.put("protectedChains", List.of(chain));
            addRepair(repairs, "PROTECTED_CHAIN_REBUILT", "Rebuilt missing protected chain from final EDL node order.");
        }
    }

    private void evaluateSceneContinuity(
            List<Map<String, Object>> segments,
            Evidence evidence,
            Map<String, Object> sceneCritic,
            List<Map<String, Object>> issues,
            List<Map<String, Object>> repairs
    ) {
        if (segments.size() < 2) {
            return;
        }
        int sceneChanges = 0;
        int flashCuts = 0;
        String previousScene = stringValue(segments.get(0).get("sceneId"), "");
        for (int index = 1; index < segments.size(); index++) {
            Map<String, Object> previous = segments.get(index - 1);
            Map<String, Object> current = segments.get(index);
            String currentScene = stringValue(current.get("sceneId"), "");
            if (!currentScene.isBlank() && !currentScene.equals(previousScene)) {
                sceneChanges++;
            }
            double previousDuration = doubleValue(previous.get("durationSeconds"), 0.0);
            double currentDuration = doubleValue(current.get("durationSeconds"), 0.0);
            if (!currentScene.equals(previousScene) && Math.min(previousDuration, currentDuration) < 0.75) {
                flashCuts++;
            }
            if (!currentScene.isBlank() && !evidence.scenesById().containsKey(currentScene)) {
                addIssue(issues, repairs, "medium", "UNKNOWN_SCENE_ID", "Segment references a scene id not present in scene analysis.", false);
            }
            previousScene = currentScene;
        }
        if (flashCuts > 0) {
            addIssue(issues, repairs, "medium", "FLASH_CUT_RISK", "Very short segments occur around scene changes.", false);
        }
        if (sceneChanges > Math.max(4, segments.size() - 1) && containsAny(String.valueOf(sceneCritic), "jarring", "unstable", "bad transition")) {
            addIssue(issues, repairs, "medium", "SCENE_TRANSITION_RISK", "Scene critic signaled transition risk across a visually jumpy candidate.", false);
        }
    }

    private Map<String, Object> repairCaptions(
            CreatorShortVideo video,
            Map<String, Object> captionPlan,
            List<Map<String, Object>> segments,
            double duration,
            List<Map<String, Object>> issues,
            List<Map<String, Object>> repairs
    ) {
        List<Map<String, Object>> captions = listOfMaps(captionPlan.get("captions"));
        String rebuildReason = captionRebuildReason(captions, segments, duration);
        boolean rebuilt = !rebuildReason.isBlank();
        if (rebuilt) {
            captions = captionsFromSegments(segments);
            addIssue(issues, repairs, "medium", "CAPTIONS_REBUILT", rebuildReason, true);
            log.info(
                    "Short captions rebuilt during continuity repair videoId={} reason=\"{}\" segmentCount={} captionCount={} durationSeconds={}",
                    video == null ? null : video.getId(),
                    rebuildReason,
                    segments.size(),
                    captions.size(),
                    round3(duration)
            );
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> caption : captions) {
            Map<String, Object> copy = new LinkedHashMap<>(caption);
            String nodeId = stringValue(copy.get("nodeId"), "");
            Map<String, Object> segment = segmentForCaption(segments, nodeId, doubleValue(copy.get("start"), 0.0));
            double start = clamp(doubleValue(copy.get("start"), doubleValue(segment.get("timelineStart"), 0.0)), 0.0, Math.max(0.0, duration - 0.1));
            double end = clamp(doubleValue(copy.get("end"), doubleValue(segment.get("timelineEnd"), start + 1.5)), start + MIN_CAPTION_SECONDS, duration + 0.05);
            if (end <= start) {
                end = Math.min(duration, start + MIN_CAPTION_SECONDS);
            }
            putIfChanged(copy, "start", round3(start), repairs, "CAPTION_START_REPAIRED", "Clamped caption start to final EDL timeline.");
            putIfChanged(copy, "end", round3(end), repairs, "CAPTION_END_REPAIRED", "Clamped caption end to final EDL timeline.");
            String text = normalizeCaptionText(stringValue(copy.get("text"), ""));
            if (text.isBlank() || isGenericCaptionText(text)) {
                copy.put("text", captionText(captionSourceText(segment)));
                addRepair(repairs, "CAPTION_TEXT_REPAIRED", "Filled missing caption text from segment transcript.");
            } else if (text.length() > MAX_CAPTION_CHARS) {
                copy.put("text", captionText(text));
                addRepair(repairs, "CAPTION_TEXT_REPAIRED", "Shortened overlong caption text for render safety.");
            } else if (!text.equals(copy.get("text"))) {
                copy.put("text", text);
                addRepair(repairs, "CAPTION_TEXT_REPAIRED", "Normalized caption whitespace.");
            }
            if (nodeId.isBlank() && segment.get("nodeId") != null) {
                copy.put("nodeId", segment.get("nodeId"));
                addRepair(repairs, "CAPTION_NODE_ID_REPAIRED", "Aligned caption with segment node id.");
            }
            normalized.add(copy);
        }
        normalized.sort(Comparator.comparingDouble(caption -> doubleValue(caption.get("start"), 0.0)));
        double previousEnd = 0.0;
        List<Map<String, Object>> timelineSafe = new ArrayList<>();
        for (Map<String, Object> caption : normalized) {
            double start = doubleValue(caption.get("start"), 0.0);
            if (start < previousEnd - TIMING_EPSILON) {
                double delta = previousEnd - start;
                start = previousEnd;
                caption.put("start", round3(start));
                caption.put("end", round3(Math.max(start + MIN_CAPTION_SECONDS, doubleValue(caption.get("end"), start + MIN_CAPTION_SECONDS) + delta)));
                addRepair(repairs, "CAPTION_OVERLAP_REPAIRED", "Removed caption timeline overlap.");
            }
            double end = Math.min(duration, doubleValue(caption.get("end"), start + MIN_CAPTION_SECONDS));
            if (end <= start + TIMING_EPSILON) {
                addRepair(repairs, "CAPTION_DROPPED", "Dropped caption that no longer fit the final EDL timeline.");
                continue;
            }
            caption.put("end", round3(end));
            timelineSafe.add(caption);
            previousEnd = Math.max(previousEnd, end);
        }
        normalized = timelineSafe;

        captionPlan.put("source", rebuilt ? SOURCE : stringValue(captionPlan.get("source"), SOURCE));
        captionPlan.put("platform", video == null ? "youtube_shorts" : stringValue(video.getPlatform(), "youtube_shorts"));
        captionPlan.put("safeZones", captionPlan.getOrDefault("safeZones", safeZonesFor(video)));
        captionPlan.put("density", stringValue(captionPlan.get("density"), "medium"));
        captionPlan.put("captionRepairMode", rebuilt ? "FINAL_EDL_SOURCE_OF_TRUTH" : "NORMALIZED_EXISTING_PLAN");
        captionPlan.put("captionRebuilt", rebuilt);
        captionPlan.put("captionCount", normalized.size());
        captionPlan.put("spokenSegmentCoverageRatio", round3(captionCoverageRatio(segments, normalized)));
        captionPlan.put("captions", normalized);
        captionPlan.put("continuityChecked", true);
        return captionPlan;
    }

    private Map<String, Object> repairRenderManifest(
            CreatorShortVideo video,
            Map<String, Object> renderManifest,
            Map<String, Object> edl,
            List<Map<String, Object>> segments,
            double duration,
            List<Map<String, Object>> issues,
            List<Map<String, Object>> repairs
    ) {
        putIfChanged(renderManifest, "renderStatus", stringValue(renderManifest.get("renderStatus"), "PENDING_REVIEW"), repairs, "RENDER_STATUS_NORMALIZED", "Normalized render status.");
        putIfChanged(renderManifest, "aspectRatio", stringValue(renderManifest.get("aspectRatio"), aspectRatioFor(video)), repairs, "RENDER_ASPECT_REPAIRED", "Restored render aspect ratio.");
        putIfChanged(renderManifest, "targetDurationSeconds", targetDuration(video), repairs, "RENDER_TARGET_DURATION_REPAIRED", "Aligned render target duration.");
        putIfChanged(renderManifest, "actualDurationSeconds", round3(duration), repairs, "RENDER_ACTUAL_DURATION_REPAIRED", "Aligned render actual duration.");
        putIfChanged(renderManifest, "safeZones", safeZonesFor(video), repairs, "RENDER_SAFE_ZONES_REPAIRED", "Aligned render safe zones.");
        putIfChanged(renderManifest, "sourceSegments", segments, repairs, "RENDER_SOURCE_SEGMENTS_REPAIRED", "Aligned render source segments with final EDL.");
        renderManifest.put("continuityRenderPolicy", stringValue(edl.get("renderPolicy"), "concat_source_segments_in_timeline_order"));
        return renderManifest;
    }

    private void repairVisualPlan(Map<String, Object> renderManifest, List<Map<String, Object>> segments, List<Map<String, Object>> repairs) {
        Map<String, Object> visualPlan = mapValue(renderManifest.get("visualEnhancementPlan"));
        if (visualPlan.isEmpty()) {
            return;
        }
        List<Map<String, Object>> segmentPlans = listOfMaps(visualPlan.get("segmentPlans"));
        if (segmentPlans.size() != segments.size()) {
            segmentPlans = new ArrayList<>();
            for (int index = 0; index < segments.size(); index++) {
                Map<String, Object> segment = segments.get(index);
                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("index", index);
                plan.put("nodeId", stringValue(segment.get("nodeId"), ""));
                plan.put("sceneId", stringValue(segment.get("sceneId"), ""));
                plan.put("sourceStart", segment.get("sourceStart"));
                plan.put("sourceEnd", segment.get("sourceEnd"));
                plan.put("timelineStart", segment.get("timelineStart"));
                plan.put("timelineEnd", segment.get("timelineEnd"));
                plan.put("filterProfile", stringValue(visualPlan.get("filterProfile"), "balanced_social"));
                applySpeakerFocusToVisualPlan(plan, segment, visualPlan);
                segmentPlans.add(plan);
            }
            addRepair(repairs, "VISUAL_SEGMENT_PLANS_REBUILT", "Rebuilt visual segment plans after continuity normalization.");
        } else {
            for (int index = 0; index < segments.size(); index++) {
                Map<String, Object> segment = segments.get(index);
                Map<String, Object> plan = segmentPlans.get(index);
                putIfChanged(plan, "index", index, repairs, "VISUAL_SEGMENT_INDEX_REPAIRED", "Aligned visual segment index.");
                putIfChanged(plan, "nodeId", stringValue(segment.get("nodeId"), ""), repairs, "VISUAL_SEGMENT_NODE_REPAIRED", "Aligned visual segment node id.");
                putIfChanged(plan, "sceneId", stringValue(segment.get("sceneId"), ""), repairs, "VISUAL_SEGMENT_SCENE_REPAIRED", "Aligned visual segment scene id.");
                putIfChanged(plan, "sourceStart", segment.get("sourceStart"), repairs, "VISUAL_SEGMENT_SOURCE_START_REPAIRED", "Aligned visual segment source start.");
                putIfChanged(plan, "sourceEnd", segment.get("sourceEnd"), repairs, "VISUAL_SEGMENT_SOURCE_END_REPAIRED", "Aligned visual segment source end.");
                putIfChanged(plan, "timelineStart", segment.get("timelineStart"), repairs, "VISUAL_SEGMENT_TIMELINE_START_REPAIRED", "Aligned visual segment timeline start.");
                putIfChanged(plan, "timelineEnd", segment.get("timelineEnd"), repairs, "VISUAL_SEGMENT_TIMELINE_END_REPAIRED", "Aligned visual segment timeline end.");
                applySpeakerFocusToVisualPlan(plan, segment, visualPlan);
            }
        }
        visualPlan.put("segmentPlans", segmentPlans);
        if (segments.stream().anyMatch(segment -> !mapValue(segment.get("speakerFocus")).isEmpty())) {
            visualPlan.put("speakerFocusEnabled", true);
            visualPlan.put("speakerFocusSource", "transcript_diarization");
            visualPlan.put("speakerFocusFallback", "center_crop_when_speaker_unknown");
        }
        visualPlan.put("continuityChecked", true);
        visualPlan.put("continuityCheckedAt", OffsetDateTime.now().toString());
        renderManifest.put("visualEnhancementPlan", visualPlan);
    }

    private void applySpeakerFocus(Map<String, Object> segment, Map<String, Object> node, Evidence evidence) {
        String speaker = stringValue(firstNonEmpty(
                segment.get("speaker"),
                segment.get("activeSpeaker"),
                node == null ? null : node.get("speaker"),
                node == null ? null : node.get("role")
        ), "").trim();
        if (speaker.isBlank()) {
            return;
        }
        String speakerKey = speakerKey(speaker);
        String anchor = evidence.speakerAnchorByLabel().getOrDefault(speakerKey, "center");
        Map<String, Object> focus = new LinkedHashMap<>();
        focus.put("enabled", true);
        focus.put("source", "transcript_diarization");
        focus.put("speaker", speaker);
        focus.put("speakerKey", speakerKey);
        focus.put("cropAnchor", anchor);
        focus.put("layoutMode", "speaker_focus_crop");
        focus.put("fallback", "center_crop_if_visual_mapping_unknown");
        segment.put("speaker", speaker);
        segment.put("activeSpeaker", speaker);
        segment.put("speakerFocus", focus);
        segment.put("speakerFocusCropAnchor", anchor);
    }

    private void applySpeakerFocusToVisualPlan(Map<String, Object> plan, Map<String, Object> segment, Map<String, Object> visualPlan) {
        Map<String, Object> focus = mapValue(segment.get("speakerFocus"));
        if (focus.isEmpty()) {
            plan.putIfAbsent("layoutMode", stringValue(visualPlan.get("layoutMode"), "fill_crop"));
            plan.putIfAbsent("cropMode", stringValue(visualPlan.get("cropMode"), "center_safe_crop"));
            plan.putIfAbsent("cropAnchor", "center");
            return;
        }
        String anchor = stringValue(firstNonEmpty(focus.get("cropAnchor"), segment.get("speakerFocusCropAnchor")), "center");
        plan.put("speaker", stringValue(firstNonEmpty(segment.get("speaker"), focus.get("speaker")), ""));
        plan.put("speakerFocus", focus);
        plan.put("speakerFocusCropAnchor", anchor);
        plan.put("layoutMode", "speaker_focus_crop");
        plan.put("cropMode", "speaker_focus_crop");
        plan.put("cropAnchor", anchor);
        plan.put("allowCrop", true);
    }

    private Map<String, String> speakerAnchors(List<Map<String, Object>> transcript) {
        LinkedHashSet<String> speakers = new LinkedHashSet<>();
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            String speaker = speakerKey(firstNonEmpty(
                    node == null ? null : node.get("speaker"),
                    node == null ? null : node.get("role")
            ));
            if (!speaker.isBlank()) {
                speakers.add(speaker);
            }
        }
        Map<String, String> anchors = new LinkedHashMap<>();
        if (speakers.isEmpty()) {
            return anchors;
        }
        List<String> ordered = new ArrayList<>(speakers);
        if (ordered.size() == 1) {
            anchors.put(ordered.get(0), "center");
            return anchors;
        }
        for (int index = 0; index < ordered.size(); index++) {
            String anchor = index == 0 ? "left" : (index == 1 ? "right" : "center");
            anchors.put(ordered.get(index), anchor);
        }
        return anchors;
    }

    private String speakerKey(Object value) {
        return stringValue(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private void ensureProtectedChains(Map<String, Object> edl, List<Map<String, Object>> segments, Evidence evidence, List<Map<String, Object>> repairs) {
        List<Map<String, Object>> chains = listOfMaps(edl.get("protectedChains"));
        if (!chains.isEmpty()) {
            return;
        }
        List<String> ids = segmentNodeIds(segments);
        if (ids.isEmpty()) {
            return;
        }
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("type", "SEQUENTIAL");
        chain.put("nodeIds", ids);
        chain.put("reason", "Continuity critic restored protected chain for final render order.");
        chain.put("graphProtectedChainCount", evidence.protectedChains().size());
        edl.put("protectedChains", List.of(chain));
        addRepair(repairs, "PROTECTED_CHAIN_RESTORED", "Restored protected chain metadata.");
    }

    private Evidence evidence(List<Map<String, Object>> transcript, List<Map<String, Object>> scenes, Map<String, Object> graph) {
        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        Map<String, Integer> ordinalByNodeId = new LinkedHashMap<>();
        for (int index = 0; index < transcript.size(); index++) {
            Map<String, Object> node = transcript.get(index);
            String id = stringValue(firstNonEmpty(node.get("id"), node.get("nodeId")), "");
            if (!id.isBlank()) {
                nodesById.put(id, node);
                ordinalByNodeId.put(id, index);
            }
        }
        Map<String, Map<String, Object>> scenesById = new LinkedHashMap<>();
        for (Map<String, Object> scene : scenes) {
            String id = stringValue(scene.get("id"), "");
            if (!id.isBlank()) {
                scenesById.put(id, scene);
            }
        }
        return new Evidence(
                transcript,
                scenes,
                nodesById,
                ordinalByNodeId,
                scenesById,
                speakerAnchors(transcript),
                listOfMaps(graph == null ? null : graph.get("edges")),
                listOfMaps(graph == null ? null : graph.get("protectedChains"))
        );
    }

    private List<Map<String, Object>> fallbackSegments(CreatorShortVideo video, List<Map<String, Object>> transcript, int rank) {
        List<Map<String, Object>> segments = new ArrayList<>();
        double cursor = 0.0;
        int target = targetDuration(video);
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            if (cursor >= target) {
                break;
            }
            double start = doubleValue(node.get("start"), 0.0);
            double end = Math.max(start + MIN_SEGMENT_SECONDS, doubleValue(node.get("end"), start + 1.0));
            double duration = Math.min(end - start, target - cursor);
            if (duration <= 0.0) {
                continue;
            }
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("nodeId", stringValue(firstNonEmpty(node.get("id"), node.get("nodeId")), ""));
            segment.put("sceneId", stringValue(node.get("sceneId"), ""));
            segment.put("sourceStart", round3(start));
            segment.put("sourceEnd", round3(start + duration));
            segment.put("timelineStart", round3(cursor));
            segment.put("timelineEnd", round3(cursor + duration));
            segment.put("durationSeconds", round3(duration));
            segment.put("operation", "KEEP");
            segment.put("reason", "Continuity critic fallback segment.");
            segment.put("transcript", stringValue(firstNonEmpty(node.get("transcript"), node.get("text")), ""));
            segments.add(segment);
            cursor += duration;
        }
        return segments;
    }

    private boolean audioCutRisk(Map<String, Object> segment, Evidence evidence) {
        String text = stringValue(segment.get("transcript"), "").trim();
        if (text.isBlank() || text.length() < 16) {
            return false;
        }
        boolean unfinishedText = !text.matches(".*[.!?\"']$") && text.split("\\s+").length > 6;
        String nodeId = stringValue(segment.get("nodeId"), "");
        Map<String, Object> node = evidence.nodesById().getOrDefault(nodeId, Map.of());
        double sourceStart = doubleValue(segment.get("sourceStart"), 0.0);
        double sourceEnd = doubleValue(segment.get("sourceEnd"), sourceStart);
        double nodeStart = doubleValue(node.get("start"), sourceStart);
        double nodeEnd = doubleValue(node.get("end"), sourceEnd);
        boolean cutsInsideNode = sourceStart > nodeStart + 0.2 || sourceEnd < nodeEnd - 0.2;
        return unfinishedText && cutsInsideNode;
    }

    private Map<String, Object> segmentForCaption(List<Map<String, Object>> segments, String nodeId, double captionStart) {
        for (Map<String, Object> segment : segments) {
            if (!nodeId.isBlank() && nodeId.equals(stringValue(segment.get("nodeId"), ""))) {
                return segment;
            }
        }
        for (Map<String, Object> segment : segments) {
            double start = doubleValue(segment.get("timelineStart"), 0.0);
            double end = doubleValue(segment.get("timelineEnd"), start);
            if (captionStart >= start && captionStart <= end) {
                return segment;
            }
        }
        return segments.isEmpty() ? new LinkedHashMap<>() : segments.get(0);
    }

    private Map<String, Object> nodeAt(List<Map<String, Object>> transcript, double timestamp) {
        for (Map<String, Object> node : transcript == null ? List.<Map<String, Object>>of() : transcript) {
            double start = doubleValue(node.get("start"), 0.0);
            double end = doubleValue(node.get("end"), start);
            if (timestamp >= start && timestamp <= end) {
                return node;
            }
        }
        return new LinkedHashMap<>();
    }

    private String sceneIdAt(List<Map<String, Object>> scenes, double timestamp) {
        for (Map<String, Object> scene : scenes == null ? List.<Map<String, Object>>of() : scenes) {
            double start = doubleValue(scene.get("start"), 0.0);
            double end = doubleValue(scene.get("end"), start);
            if (timestamp >= start && timestamp <= end) {
                return stringValue(scene.get("id"), "");
            }
        }
        return "";
    }

    private List<Map<String, Object>> captionsFromSegments(List<Map<String, Object>> segments) {
        List<Map<String, Object>> captions = new ArrayList<>();
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            String text = captionSourceText(segment);
            if (text.isBlank()) {
                continue;
            }
            double start = doubleValue(segment.get("timelineStart"), doubleValue(segment.get("start"), 0.0));
            double end = doubleValue(segment.get("timelineEnd"), doubleValue(segment.get("end"), start + MIN_CAPTION_SECONDS));
            if (end <= start) {
                end = start + MIN_CAPTION_SECONDS;
            }
            double duration = Math.max(MIN_CAPTION_SECONDS, end - start);
            List<String> chunks = fitCaptionChunksToDuration(splitCaptionText(text), duration);
            for (int index = 0; index < chunks.size(); index++) {
                double chunkStart = start + (duration * index / chunks.size());
                double chunkEnd = index == chunks.size() - 1 ? end : start + (duration * (index + 1) / chunks.size());
                Map<String, Object> caption = new LinkedHashMap<>();
                caption.put("start", round3(chunkStart));
                caption.put("end", round3(Math.max(chunkStart + MIN_CAPTION_SECONDS, chunkEnd)));
                caption.put("text", chunks.get(index));
                caption.put("nodeId", segment.get("nodeId"));
                caption.put("source", "final_edl_segment_transcript");
                captions.add(caption);
            }
        }
        return captions;
    }

    private String captionRebuildReason(List<Map<String, Object>> captions, List<Map<String, Object>> segments, double duration) {
        int spokenSegmentCount = spokenSegmentCount(segments);
        if (spokenSegmentCount <= 0) {
            return "";
        }
        if (captions.isEmpty()) {
            return "Rebuilt missing captions from final EDL segments.";
        }
        if (duration <= 0) {
            return "Rebuilt captions because final EDL duration was not safe to trust.";
        }
        double previousStart = -1.0;
        double previousEnd = 0.0;
        for (Map<String, Object> caption : captions) {
            double start = doubleValue(caption.get("start"), -1.0);
            double end = doubleValue(caption.get("end"), -1.0);
            if (start < 0 || end <= start || end > duration + 1.0) {
                return "Rebuilt captions whose timings were outside the final EDL timeline.";
            }
            if (start < previousStart - TIMING_EPSILON || start < previousEnd - TIMING_EPSILON) {
                return "Rebuilt captions because their timings were overlapping or non-monotonic.";
            }
            previousStart = start;
            previousEnd = end;
            String text = normalizeCaptionText(stringValue(caption.get("text"), ""));
            if (text.isBlank() || isGenericCaptionText(text)) {
                return "Rebuilt placeholder captions from final EDL transcript segments.";
            }
            if (text.length() > MAX_CAPTION_CHARS) {
                return "Rebuilt overlong captions into readable timed chunks.";
            }
        }
        if (spokenSegmentCount >= 3 && captions.size() < Math.max(2, (int) Math.ceil(spokenSegmentCount * 0.6))) {
            return "Rebuilt sparse captions so final EDL speech is covered segment by segment.";
        }
        if (captionCoverageRatio(segments, captions) < 0.7) {
            return "Rebuilt captions because too many final EDL segments had no aligned caption.";
        }
        return "";
    }

    private double captionCoverageRatio(List<Map<String, Object>> segments, List<Map<String, Object>> captions) {
        int spoken = 0;
        int covered = 0;
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            if (captionSourceText(segment).isBlank()) {
                continue;
            }
            spoken++;
            if (captionCoversSegment(segment, captions)) {
                covered++;
            }
        }
        return spoken == 0 ? 1.0 : covered / (double) spoken;
    }

    private int spokenSegmentCount(List<Map<String, Object>> segments) {
        int count = 0;
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            if (!captionSourceText(segment).isBlank()) {
                count++;
            }
        }
        return count;
    }

    private boolean captionCoversSegment(Map<String, Object> segment, List<Map<String, Object>> captions) {
        String nodeId = stringValue(segment.get("nodeId"), "");
        double segmentStart = doubleValue(segment.get("timelineStart"), 0.0);
        double segmentEnd = doubleValue(segment.get("timelineEnd"), segmentStart);
        for (Map<String, Object> caption : captions == null ? List.<Map<String, Object>>of() : captions) {
            String captionNodeId = stringValue(caption.get("nodeId"), "");
            if (!nodeId.isBlank() && nodeId.equals(captionNodeId)) {
                return true;
            }
            double captionStart = doubleValue(caption.get("start"), -1.0);
            double captionEnd = doubleValue(caption.get("end"), -1.0);
            if (Math.min(segmentEnd, captionEnd) - Math.max(segmentStart, captionStart) >= 0.15) {
                return true;
            }
        }
        return false;
    }

    private List<String> fitCaptionChunksToDuration(List<String> chunks, double duration) {
        if (chunks.size() <= 1) {
            return chunks;
        }
        int maxChunks = Math.max(1, (int) Math.floor(Math.max(MIN_CAPTION_SECONDS, duration) / MIN_CAPTION_SECONDS));
        if (chunks.size() <= maxChunks) {
            return chunks;
        }
        return splitCaptionTextByCount(String.join(" ", chunks), maxChunks);
    }

    private List<String> splitCaptionTextByCount(String value, int count) {
        List<String> chunks = new ArrayList<>();
        String text = normalizeCaptionText(value);
        if (text.isBlank()) {
            return chunks;
        }
        int targetLength = Math.max(TARGET_CAPTION_CHARS, (int) Math.ceil(text.length() / (double) Math.max(1, count)));
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            boolean lastAllowedChunk = chunks.size() >= count - 1;
            int nextLength = current.length() + (current.isEmpty() ? 0 : 1) + word.length();
            if (!lastAllowedChunk && current.length() > 0 && nextLength > targetLength) {
                chunks.add(current.toString());
                current = new StringBuilder(word);
            } else {
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(word);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private List<String> splitCaptionText(String value) {
        List<String> chunks = new ArrayList<>();
        String text = normalizeCaptionText(value);
        if (text.isBlank()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.length() > MAX_CAPTION_CHARS) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current = new StringBuilder();
                }
                for (int index = 0; index < word.length(); index += MAX_CAPTION_CHARS) {
                    chunks.add(word.substring(index, Math.min(word.length(), index + MAX_CAPTION_CHARS)));
                }
                continue;
            }
            int nextLength = current.length() + (current.isEmpty() ? 0 : 1) + word.length();
            if (current.length() > 0 && nextLength > TARGET_CAPTION_CHARS) {
                chunks.add(current.toString());
                current = new StringBuilder(word);
            } else {
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(word);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private String captionSourceText(Map<String, Object> segment) {
        return normalizeCaptionText(stringValue(firstNonEmpty(
                segment.get("transcript"),
                segment.get("text"),
                segment.get("summary"),
                segment.get("reason")
        ), ""));
    }

    private List<String> segmentNodeIds(List<Map<String, Object>> segments) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            String id = stringValue(segment.get("nodeId"), "");
            if (!id.isBlank() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Map<String, Integer> orderMap(List<String> ids) {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            order.put(ids.get(index), index);
        }
        return order;
    }

    private boolean isProtectedEdge(String type) {
        return containsAny(type, "QUESTION_ANSWER", "QA", "SETUP_PAYOFF", "PROBLEM_SOLUTION", "CAUSE_EFFECT", "REVEAL", "PUNCHLINE");
    }

    private String inferredChainType(Map<String, Object> edl, Map<String, Object> storyCritic) {
        String strategy = stringValue(edl.get("strategy"), "").toUpperCase(Locale.ROOT);
        if (strategy.contains("QA")) return "QUESTION_ANSWER";
        if (strategy.contains("PROBLEM")) return "PROBLEM_SOLUTION";
        if (strategy.contains("REVEAL")) return "SETUP_PAYOFF";
        if (strategy.contains("PUNCHLINE")) return "SETUP_PAYOFF";
        String storyText = String.valueOf(storyCritic == null ? "" : storyCritic).toUpperCase(Locale.ROOT);
        if (storyText.contains("QUESTION")) return "QUESTION_ANSWER";
        if (storyText.contains("PROBLEM")) return "PROBLEM_SOLUTION";
        return "SEQUENTIAL";
    }

    private double durationFromSegments(List<Map<String, Object>> segments) {
        double duration = 0.0;
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            duration += Math.max(0.0, doubleValue(segment.get("durationSeconds"), doubleValue(segment.get("timelineEnd"), 0.0) - doubleValue(segment.get("timelineStart"), 0.0)));
        }
        return duration;
    }

    private List<String> safeZonesFor(CreatorShortVideo video) {
        String platform = video == null ? "youtube_shorts" : stringValue(video.getPlatform(), "youtube_shorts").toLowerCase(Locale.ROOT);
        return switch (platform) {
            case "linkedin" -> List.of("caption_safe_bottom", "profile_safe_top");
            case "x" -> List.of("square_center_safe", "caption_safe_bottom");
            default -> List.of("top_caption_safe", "bottom_ui_safe", "right_action_rail_safe");
        };
    }

    private String aspectRatioFor(CreatorShortVideo video) {
        String platform = video == null ? "youtube_shorts" : stringValue(video.getPlatform(), "youtube_shorts").toLowerCase(Locale.ROOT);
        return switch (platform) {
            case "linkedin" -> "4:5";
            case "x" -> "1:1";
            default -> "9:16";
        };
    }

    private int targetDuration(CreatorShortVideo video) {
        return Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());
    }

    private String captionText(String value) {
        String text = normalizeCaptionText(value);
        if (text.length() <= MAX_CAPTION_CHARS) {
            return text;
        }
        int breakPoint = text.lastIndexOf(' ', MAX_CAPTION_CHARS);
        if (breakPoint < 24) {
            breakPoint = MAX_CAPTION_CHARS;
        }
        return text.substring(0, breakPoint).trim();
    }

    private String normalizeCaptionText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private boolean isGenericCaptionText(String value) {
        String text = normalizeCaptionText(value).toLowerCase(Locale.ROOT);
        return text.isBlank()
                || "...".equals(text)
                || "caption".equals(text)
                || "caption text".equals(text)
                || "wait for the useful part".equals(text)
                || "watch the full chain before judging the takeaway".equals(text)
                || text.startsWith("watch the full chain")
                || text.startsWith("wait for ");
    }

    private void addIssue(List<Map<String, Object>> issues, List<Map<String, Object>> repairs, String severity, String code, String summary, boolean repaired) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.put("summary", summary);
        issue.put("repaired", repaired);
        issue.put("createdAt", OffsetDateTime.now().toString());
        issues.add(issue);
        if (repaired) {
            addRepair(repairs, code, summary);
        }
    }

    private void addRepair(List<Map<String, Object>> repairs, String code, String summary) {
        Map<String, Object> repair = new LinkedHashMap<>();
        repair.put("code", code);
        repair.put("summary", summary);
        repair.put("createdAt", OffsetDateTime.now().toString());
        repairs.add(repair);
    }

    private void putIfChanged(Map<String, Object> map, String key, Object value, List<Map<String, Object>> repairs, String code, String summary) {
        Object existing = map.get(key);
        if (!deepEquals(existing, value)) {
            map.put(key, value);
            addRepair(repairs, code, summary);
        }
    }

    private boolean noUnresolvedHigh(List<Map<String, Object>> issues) {
        for (Map<String, Object> issue : issues == null ? List.<Map<String, Object>>of() : issues) {
            if ("high".equalsIgnoreCase(stringValue(issue.get("severity"), "")) && !Boolean.TRUE.equals(issue.get("repaired"))) {
                return false;
            }
        }
        return true;
    }

    private boolean deepEquals(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Number || right instanceof Number) {
            try {
                return Math.abs(Double.parseDouble(String.valueOf(left)) - Double.parseDouble(String.valueOf(right))) < 0.0001;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private boolean containsAny(String value, String... needles) {
        String haystack = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private double midpoint(double start, double end) {
        return start + Math.max(0.0, end - start) / 2.0;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = mapValue(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    private List<String> listOfStrings(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private Object firstNonEmpty(Object... values) {
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

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
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

    private record Evidence(
            List<Map<String, Object>> transcript,
            List<Map<String, Object>> scenes,
            Map<String, Map<String, Object>> nodesById,
            Map<String, Integer> ordinalByNodeId,
            Map<String, Map<String, Object>> scenesById,
            Map<String, String> speakerAnchorByLabel,
            List<Map<String, Object>> edges,
            List<Map<String, Object>> protectedChains
    ) {
    }

    private record AuditOutcome(
            Map<String, Object> candidate,
            Map<String, Object> audit,
            List<Map<String, Object>> repairs
    ) {
    }

    public record ContinuityCriticResult(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
