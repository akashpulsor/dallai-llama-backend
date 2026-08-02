package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ShortCandidateCriticRepairService {

    private static final Logger log = LoggerFactory.getLogger(ShortCandidateCriticRepairService.class);
    private static final int TARGET_CAPTION_CHARS = 56;
    private static final int MAX_CAPTION_CHARS = 72;
    private static final double MIN_CAPTION_SECONDS = 0.5;
    private static final double TIMING_EPSILON = 0.05;

    public CandidateCriticRepairResult repair(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> candidatePayloads,
            List<Map<String, Object>> compressionPlans
    ) {
        List<Map<String, Object>> candidates = copyList(candidatePayloads);
        List<Map<String, Object>> plans = copyList(compressionPlans);
        if (candidates.isEmpty()) {
            for (Map<String, Object> plan : plans) {
                candidates.add(candidateFromPlan(video, plan));
            }
        }

        int requested = Math.max(1, Math.min(video == null || video.getRequestedShorts() == null ? 20 : video.getRequestedShorts(), 50));
        while (candidates.size() < requested && !plans.isEmpty()) {
            candidates.add(candidateFromPlan(video, plans.get(candidates.size() % plans.size())));
        }

        List<Map<String, Object>> repaired = new ArrayList<>();
        List<Map<String, Object>> repairEvents = new ArrayList<>();
        int compressionPass = 0;
        int hookPass = 0;
        int captionPass = 0;
        int continuityPass = 0;
        int globalPass = 0;
        int limit = Math.min(candidates.size(), requested);
        for (int index = 0; index < limit; index++) {
            Map<String, Object> candidate = new LinkedHashMap<>(candidates.get(index));
            Map<String, Object> plan = plans.isEmpty() ? new LinkedHashMap<>() : plans.get(index % plans.size());
            RepairOutcome outcome = repairCandidate(video, videoDna, transcript, graph, candidate, plan, index + 1);
            repaired.add(outcome.candidate());
            repairEvents.addAll(outcome.repairs());
            if (isPass(outcome.critics().get("compressionCritic"))) compressionPass++;
            if (isPass(outcome.critics().get("hookCritic"))) hookPass++;
            if (isPass(outcome.critics().get("captionCritic"))) captionPass++;
            if (isPass(outcome.critics().get("continuityCritic"))) continuityPass++;
            if (isPass(outcome.critics().get("globalCritic"))) globalPass++;
        }

        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceRow("COMPRESSION_CRITIC", status(compressionPass, repaired.size()), "Compression critic verified exact timestamps, target duration, and protected chains.", confidence(compressionPass, repaired.size()), Map.of("passed", compressionPass, "total", repaired.size())));
        trace.add(traceRow("HOOK_CRITIC", status(hookPass, repaired.size()), "Hook critic checked candidate hook type, first beat, and title clarity.", confidence(hookPass, repaired.size()), Map.of("passed", hookPass, "total", repaired.size())));
        trace.add(traceRow("CAPTION_CRITIC", status(captionPass, repaired.size()), "Caption critic checked caption timing and repaired missing or unsafe captions.", confidence(captionPass, repaired.size()), Map.of("passed", captionPass, "total", repaired.size())));
        trace.add(traceRow("CONTINUITY_CRITIC", status(continuityPass, repaired.size()), "Continuity critic checked source order and timeline continuity.", confidence(continuityPass, repaired.size()), Map.of("passed", continuityPass, "total", repaired.size())));
        trace.add(traceRow("GLOBAL_CRITIC", status(globalPass, repaired.size()), "Global critic checked candidate readiness across compression, hook, captions, and render metadata.", confidence(globalPass, repaired.size()), Map.of("passed", globalPass, "total", repaired.size())));
        trace.add(traceRow("TARGETED_REPAIR", repairEvents.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_REPAIRS", repairEvents.isEmpty() ? "No targeted repairs were required." : "Applied targeted repairs to candidate EDL, hooks, captions, or manifests.", 0.9, Map.of("repairCount", repairEvents.size())));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "deterministic_critic_repair_worker");
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("candidateCount", repaired.size());
        metadata.put("repairCount", repairEvents.size());
        metadata.put("repairs", repairEvents);

        return new CandidateCriticRepairResult(repaired, trace, metadata);
    }

    private RepairOutcome repairCandidate(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            Map<String, Object> candidate,
            Map<String, Object> plan,
            int rank
    ) {
        List<Map<String, Object>> repairs = new ArrayList<>();
        Map<String, Object> planEdl = mapValue(plan.get("editDecisionList"));
        Map<String, Object> edl = planEdl.isEmpty() ? mapValue(candidate.get("editDecisionList")) : new LinkedHashMap<>(planEdl);
        if (edl.isEmpty()) {
            edl = fallbackEdl(video, transcript, rank);
            repairs.add(repair("editDecisionList", "Created EDL because neither planner nor compression worker returned one."));
        } else if (!Boolean.TRUE.equals(edl.get("exactTimestampEdl"))) {
            edl.put("exactTimestampEdl", true);
            edl.put("source", stringValue(edl.get("source"), "critic_repair_worker"));
            repairs.add(repair("editDecisionList", "Marked EDL as exact timestamp after timestamp normalization."));
        }
        normalizeEdl(edl);
        candidate.put("editDecisionList", edl);
        candidate.put("durationSeconds", Math.max(1, (int) Math.round(doubleValue(edl.get("actualDurationSeconds"), doubleValue(candidate.get("durationSeconds"), targetDuration(video))))));
        if (plan.get("score") != null) {
            candidate.put("score", plan.get("score"));
        }

        Map<String, Object> existingMetadata = mapValue(candidate.get("metadata"));
        boolean productionHookGenerated = "deterministic_hook_generation_worker".equals(stringValue(existingMetadata.get("hookSource"), ""));
        String hookType = stringValue(candidate.get("hookType"), "");
        if (hookType.isBlank() || ("Insight Hook".equalsIgnoreCase(hookType) && !productionHookGenerated)) {
            hookType = stringValue(plan.get("hookType"), hookTypeForEdl(edl, rank));
            candidate.put("hookType", hookType);
            repairs.add(repair("hook", productionHookGenerated ? "Preserved production hook source while filling missing hook type." : "Filled hook type from compression chain."));
        }
        if (stringValue(candidate.get("title"), "").isBlank() || stringValue(candidate.get("title"), "").toLowerCase(Locale.ROOT).startsWith("candidate")) {
            candidate.put("title", titleFromEdl(video, edl, rank));
            repairs.add(repair("hook", "Repaired weak candidate title."));
        }

        Map<String, Object> captionPlan = mapValue(candidate.get("captionPlan"));
        CriticResult initialCaptionCritic = captionCritic(captionPlan, edl);
        if (!initialCaptionCritic.passed()) {
            captionPlan = captionPlan(video, edl);
            candidate.put("captionPlan", captionPlan);
            repairs.add(repair("captionPlan", "Generated captions from exact EDL transcript segments."));
            log.info(
                    "Short caption plan rebuilt during candidate repair videoId={} rank={} issues={} captionCount={} durationSeconds={}",
                    video == null ? null : video.getId(),
                    rank,
                    initialCaptionCritic.issues(),
                    listOfMaps(captionPlan.get("captions")).size(),
                    edl.get("actualDurationSeconds")
            );
        }

        Map<String, Object> renderManifest = mapValue(candidate.get("renderManifest"));
        repairRenderManifest(video, renderManifest, edl);
        candidate.put("renderManifest", renderManifest);

        CriticResult compressionCritic = compressionCritic(edl, targetDuration(video));
        CriticResult hookCritic = hookCritic(candidate, edl);
        CriticResult captionCritic = captionCritic(captionPlan, edl);
        CriticResult continuityCritic = continuityCritic(edl);
        CriticResult globalCritic = globalCritic(compressionCritic, hookCritic, captionCritic, continuityCritic);

        Map<String, Object> critics = new LinkedHashMap<>();
        critics.put("compressionCritic", compressionCritic.toMap());
        critics.put("hookCritic", hookCritic.toMap());
        critics.put("captionCritic", captionCritic.toMap());
        critics.put("continuityCritic", continuityCritic.toMap());
        critics.put("globalCritic", globalCritic.toMap());

        Map<String, Object> metadata = mapValue(candidate.get("metadata"));
        Map<String, Object> planMetadata = mapValue(plan.get("metadata"));
        if (!planMetadata.isEmpty()) {
            metadata.put("compressionPlanMetadata", planMetadata);
            if (planMetadata.get("interestingnessScore") != null) {
                metadata.put("interestingnessScore", planMetadata.get("interestingnessScore"));
            }
        }
        metadata.putIfAbsent("source", "short_video_cursor");
        metadata.put("compressionSource", stringValue(edl.get("source"), "deterministic_compression_worker"));
        metadata.put("chain", chainLabel(edl));
        metadata.put("critics", critics);
        metadata.put("repairs", repairs);
        metadata.put("repairStatus", repairs.isEmpty() ? "NO_REPAIR_NEEDED" : "REPAIRED");
        metadata.put("videoType", stringValue(videoDna == null ? null : videoDna.get("primaryType"), "unknown"));
        metadata.put("graphSource", stringValue(graph == null ? null : graph.get("analysisSource"), "unknown"));
        candidate.put("metadata", metadata);

        return new RepairOutcome(candidate, critics, repairs);
    }

    private CriticResult compressionCritic(Map<String, Object> edl, int targetDuration) {
        List<String> issues = new ArrayList<>();
        List<Map<String, Object>> segments = listOfMaps(edl.get("segments"));
        if (segments.isEmpty()) {
            issues.add("No EDL segments.");
        }
        double actualDuration = doubleValue(edl.get("actualDurationSeconds"), durationFromSegments(segments));
        if (actualDuration <= 0) {
            issues.add("Actual duration is zero.");
        }
        if (actualDuration > targetDuration + 2.0) {
            issues.add("Actual duration exceeds target by more than two seconds.");
        }
        for (Map<String, Object> segment : segments) {
            if (!hasNumber(segment, "sourceStart") || !hasNumber(segment, "sourceEnd") || !hasNumber(segment, "timelineStart") || !hasNumber(segment, "timelineEnd")) {
                issues.add("Segment missing exact source/timeline timestamp.");
                break;
            }
        }
        if (listOfMaps(edl.get("protectedChains")).isEmpty()) {
            issues.add("No protected semantic chain recorded.");
        }
        return critic("COMPRESSION_CRITIC", issues, issues.isEmpty() ? "Exact timestamp EDL is ready." : "Compression needs attention.");
    }

    private CriticResult hookCritic(Map<String, Object> candidate, Map<String, Object> edl) {
        List<String> issues = new ArrayList<>();
        if (stringValue(candidate.get("hookType"), "").isBlank()) {
            issues.add("Missing hook type.");
        }
        if (stringValue(candidate.get("title"), "").isBlank()) {
            issues.add("Missing candidate title.");
        }
        List<Map<String, Object>> segments = listOfMaps(edl.get("segments"));
        if (!segments.isEmpty()) {
            String firstText = stringValue(segments.get(0).get("transcript"), "");
            if (firstText.length() < 8) {
                issues.add("Opening beat is too short to verify hook.");
            }
        }
        return critic("HOOK_CRITIC", issues, issues.isEmpty() ? "Hook is aligned to the opening EDL beat." : "Hook needs repair.");
    }

    private CriticResult captionCritic(Map<String, Object> captionPlan, Map<String, Object> edl) {
        List<String> issues = new ArrayList<>();
        List<Map<String, Object>> captions = listOfMaps(captionPlan.get("captions"));
        List<Map<String, Object>> segments = listOfMaps(edl.get("segments"));
        double duration = doubleValue(edl.get("actualDurationSeconds"), durationFromSegments(segments));
        if (captions.isEmpty()) {
            issues.add("No captions.");
        }
        if (duration <= 0) {
            issues.add("EDL duration is zero, so caption timing cannot be verified.");
        }
        double previousStart = -1.0;
        double previousEnd = 0.0;
        for (Map<String, Object> caption : captions) {
            double start = doubleValue(caption.get("start"), -1);
            double end = doubleValue(caption.get("end"), -1);
            if (start < 0 || end <= start || end > duration + 1.0) {
                issues.add("Caption timing is outside EDL duration.");
                break;
            }
            if (start < previousStart - TIMING_EPSILON) {
                issues.add("Caption timestamps are not monotonic.");
                break;
            }
            if (start < previousEnd - TIMING_EPSILON) {
                issues.add("Caption timings overlap.");
                break;
            }
            previousStart = start;
            previousEnd = end;
            String text = normalizeCaptionText(stringValue(caption.get("text"), ""));
            if (text.isBlank()) {
                issues.add("Caption text is blank.");
                break;
            }
            if (isGenericCaptionText(text)) {
                issues.add("Caption text is a generic placeholder instead of source transcript.");
                break;
            }
            if (text.length() > MAX_CAPTION_CHARS) {
                issues.add("Caption line is too long.");
                break;
            }
        }
        int spokenSegmentCount = spokenSegmentCount(segments);
        if (!captions.isEmpty() && spokenSegmentCount > 0) {
            int coveredSegmentCount = coveredSegmentCount(segments, captions);
            if (spokenSegmentCount >= 3 && captions.size() < Math.max(2, (int) Math.ceil(spokenSegmentCount * 0.6))) {
                issues.add("Caption plan is too sparse for the final EDL segments.");
            } else if (coveredSegmentCount < Math.max(1, (int) Math.ceil(spokenSegmentCount * 0.7))) {
                issues.add("Caption plan does not cover enough final EDL segments.");
            }
        }
        return critic("CAPTION_CRITIC", issues, issues.isEmpty() ? "Captions fit the repaired EDL timeline." : "Captions need repair.");
    }

    private CriticResult continuityCritic(Map<String, Object> edl) {
        List<String> issues = new ArrayList<>();
        List<Map<String, Object>> segments = listOfMaps(edl.get("segments"));
        double previousTimelineEnd = 0.0;
        double previousSourceStart = -1.0;
        for (Map<String, Object> segment : segments) {
            double timelineStart = doubleValue(segment.get("timelineStart"), -1.0);
            double timelineEnd = doubleValue(segment.get("timelineEnd"), -1.0);
            double sourceStart = doubleValue(segment.get("sourceStart"), -1.0);
            if (timelineStart < previousTimelineEnd - 0.05) {
                issues.add("Timeline segments overlap.");
                break;
            }
            if (sourceStart < previousSourceStart - 0.05) {
                issues.add("Source order moves backward.");
                break;
            }
            previousTimelineEnd = timelineEnd;
            previousSourceStart = sourceStart;
        }
        return critic("CONTINUITY_CRITIC", issues, issues.isEmpty() ? "EDL source order and timeline continuity pass." : "Continuity needs repair.");
    }

    private CriticResult globalCritic(CriticResult... critics) {
        List<String> issues = new ArrayList<>();
        for (CriticResult critic : critics) {
            if (!critic.passed()) {
                issues.addAll(critic.issues());
            }
        }
        return critic("GLOBAL_CRITIC", issues, issues.isEmpty() ? "Candidate is ready for review/render queue." : "Candidate has remaining review warnings.");
    }

    private CriticResult critic(String stage, List<String> issues, String summary) {
        boolean passed = issues.isEmpty();
        return new CriticResult(stage, passed ? "PASS" : "WARN", passed, passed ? 0.88 : 0.58, summary, issues);
    }

    private void normalizeEdl(Map<String, Object> edl) {
        List<Map<String, Object>> segments = listOfMaps(edl.get("segments"));
        double cursor = 0.0;
        for (Map<String, Object> segment : segments) {
            double sourceStart = doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0));
            double sourceEnd = doubleValue(segment.get("sourceEnd"), doubleValue(segment.get("end"), sourceStart + 0.1));
            sourceEnd = Math.max(sourceStart + 0.1, sourceEnd);
            double duration = sourceEnd - sourceStart;
            segment.put("sourceStart", round3(sourceStart));
            segment.put("sourceEnd", round3(sourceEnd));
            segment.put("timelineStart", round3(cursor));
            segment.put("timelineEnd", round3(cursor + duration));
            segment.put("durationSeconds", round3(duration));
            segment.putIfAbsent("operation", "KEEP");
            segment.putIfAbsent("reason", "Kept by compression worker.");
            cursor += duration;
        }
        edl.put("segments", segments);
        edl.put("version", intValue(edl.get("version"), 2));
        edl.put("source", stringValue(edl.get("source"), "deterministic_compression_worker"));
        edl.put("exactTimestampEdl", true);
        edl.put("actualDurationSeconds", round3(cursor));
        edl.put("timelineStart", 0);
        edl.put("timelineEnd", round3(cursor));
        edl.putIfAbsent("renderPolicy", "concat_source_segments_in_timeline_order");
    }

    private Map<String, Object> captionPlan(CreatorShortVideo video, Map<String, Object> edl) {
        List<Map<String, Object>> captions = captionsFromSegments(listOfMaps(edl.get("segments")));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("style", "platform-aware");
        plan.put("source", "critic_repair_worker");
        plan.put("platform", video == null ? "youtube_shorts" : stringValue(video.getPlatform(), "youtube_shorts"));
        plan.put("density", "medium");
        plan.put("safeZones", safeZonesFor(video));
        plan.put("captionRepairMode", "FINAL_EDL_SOURCE_OF_TRUTH");
        plan.put("captionCount", captions.size());
        plan.put("captions", captions);
        return plan;
    }

    private void repairRenderManifest(CreatorShortVideo video, Map<String, Object> manifest, Map<String, Object> edl) {
        manifest.put("renderStatus", "PENDING_REVIEW");
        manifest.put("aspectRatio", aspectRatioFor(video));
        manifest.put("targetDurationSeconds", targetDuration(video));
        manifest.put("actualDurationSeconds", edl.get("actualDurationSeconds"));
        manifest.put("safeZones", safeZonesFor(video));
        manifest.put("sourceSegments", edl.getOrDefault("segments", List.of()));
        manifest.put("requiresHumanReview", video == null || !"AUTO".equalsIgnoreCase(stringValue(video.getReviewMode(), "REVIEW")));
    }

    private Map<String, Object> candidateFromPlan(CreatorShortVideo video, Map<String, Object> plan) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("title", stringValue(plan.get("title"), video == null ? "Short candidate" : stringValue(video.getTitle(), "Short candidate")));
        candidate.put("durationSeconds", intValue(plan.get("durationSeconds"), targetDuration(video)));
        candidate.put("score", plan.getOrDefault("score", 90));
        candidate.put("hookType", stringValue(plan.get("hookType"), "Insight Hook"));
        candidate.put("editDecisionList", plan.getOrDefault("editDecisionList", Map.of()));
        candidate.put("metadata", plan.getOrDefault("metadata", Map.of()));
        return candidate;
    }

    private Map<String, Object> fallbackEdl(CreatorShortVideo video, List<Map<String, Object>> transcript, int rank) {
        List<Map<String, Object>> segments = new ArrayList<>();
        double cursor = 0.0;
        int target = targetDuration(video);
        for (Map<String, Object> node : transcript) {
            if (cursor >= target) {
                break;
            }
            double sourceStart = doubleValue(node.get("start"), 0.0);
            double sourceEnd = Math.max(sourceStart + 0.1, doubleValue(node.get("end"), sourceStart + 0.1));
            double duration = Math.min(sourceEnd - sourceStart, target - cursor);
            if (duration <= 0.0) {
                continue;
            }
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("nodeId", stringValue(node.get("id"), ""));
            segment.put("sceneId", stringValue(node.get("sceneId"), ""));
            segment.put("sourceStart", round3(sourceStart));
            segment.put("sourceEnd", round3(sourceStart + duration));
            segment.put("timelineStart", round3(cursor));
            segment.put("timelineEnd", round3(cursor + duration));
            segment.put("durationSeconds", round3(duration));
            segment.put("operation", "KEEP");
            segment.put("reason", "Fallback exact timestamp segment.");
            segment.put("transcript", stringValue(node.get("transcript"), ""));
            segment.put("frames", node.getOrDefault("frames", List.of()));
            segments.add(segment);
            cursor += duration;
        }
        Map<String, Object> protectedChain = new LinkedHashMap<>();
        protectedChain.put("type", "SEQUENTIAL");
        protectedChain.put("nodeIds", segments.stream().map(segment -> stringValue(segment.get("nodeId"), "")).filter(id -> !id.isBlank()).toList());
        protectedChain.put("reason", "Fallback sequential compression.");
        Map<String, Object> edl = new LinkedHashMap<>();
        edl.put("version", 2);
        edl.put("source", "critic_repair_worker");
        edl.put("exactTimestampEdl", true);
        edl.put("strategy", rank % 3 == 0 ? "KEEP_PROBLEM_SOLUTION_CHAIN" : "KEEP_QA_CHAIN");
        edl.put("rankIndex", rank);
        edl.put("targetDurationSeconds", target);
        edl.put("actualDurationSeconds", round3(cursor));
        edl.put("timelineStart", 0);
        edl.put("timelineEnd", round3(cursor));
        edl.put("segments", segments);
        edl.put("protectedChains", List.of(protectedChain));
        edl.put("renderPolicy", "concat_source_segments_in_timeline_order");
        return edl;
    }

    private String titleFromEdl(CreatorShortVideo video, Map<String, Object> edl, int rank) {
        String title = video == null ? "Short candidate" : stringValue(video.getTitle(), "Short candidate");
        if (rank == 1) {
            return truncate(title, 180);
        }
        return truncate(title + " - " + chainLabel(edl) + " " + rank, 180);
    }

    private String hookTypeForEdl(Map<String, Object> edl, int rank) {
        String strategy = stringValue(edl.get("strategy"), "");
        if (strategy.contains("QA")) return "Question Hook";
        if (strategy.contains("PROBLEM")) return "Problem/Solution";
        if (strategy.contains("REVEAL")) return "Reveal Hook";
        if (strategy.contains("PUNCHLINE")) return "Punchline Hook";
        return rank % 2 == 0 ? "Aha Moment" : "Insight Hook";
    }

    private String chainLabel(Map<String, Object> edl) {
        List<Map<String, Object>> chains = listOfMaps(edl.get("protectedChains"));
        if (!chains.isEmpty()) {
            return stringValue(chains.get(0).get("type"), "sequential").toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return stringValue(edl.get("strategy"), "sequential").toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private Map<String, Object> repair(String target, String summary) {
        Map<String, Object> repair = new LinkedHashMap<>();
        repair.put("target", target);
        repair.put("summary", summary);
        repair.put("createdAt", OffsetDateTime.now().toString());
        return repair;
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

    private String status(int passed, int total) {
        if (total <= 0) {
            return "WARN";
        }
        return passed == total ? "COMPLETED" : "COMPLETED_WITH_WARNINGS";
    }

    private double confidence(int passed, int total) {
        if (total <= 0) {
            return 0.2;
        }
        return Math.max(0.35, Math.min(0.95, 0.55 + (0.4 * passed / (double) total)));
    }

    private boolean isPass(Object value) {
        Map<String, Object> critic = mapValue(value);
        return Boolean.TRUE.equals(critic.get("passed")) || "PASS".equalsIgnoreCase(stringValue(critic.get("status"), ""));
    }

    private boolean hasNumber(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return true;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(String.valueOf(value));
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private double durationFromSegments(List<Map<String, Object>> segments) {
        double duration = 0.0;
        for (Map<String, Object> segment : segments) {
            duration += Math.max(0.0, doubleValue(segment.get("durationSeconds"), doubleValue(segment.get("timelineEnd"), 0.0) - doubleValue(segment.get("timelineStart"), 0.0)));
        }
        return duration;
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
            if (chunks.isEmpty()) {
                continue;
            }
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
        String[] words = text.split(" ");
        for (int index = 0; index < words.length; index++) {
            String word = words[index];
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

    private int spokenSegmentCount(List<Map<String, Object>> segments) {
        int count = 0;
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            if (!captionSourceText(segment).isBlank()) {
                count++;
            }
        }
        return count;
    }

    private int coveredSegmentCount(List<Map<String, Object>> segments, List<Map<String, Object>> captions) {
        int count = 0;
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            if (!captionSourceText(segment).isBlank() && captionCoversSegment(segment, captions)) {
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

    private String captionSourceText(Map<String, Object> segment) {
        return normalizeCaptionText(stringValue(firstNonEmpty(
                segment.get("transcript"),
                segment.get("text"),
                segment.get("summary"),
                segment.get("reason")
        ), ""));
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

    private int targetDuration(CreatorShortVideo video) {
        return Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());
    }

    private String aspectRatioFor(CreatorShortVideo video) {
        String platform = video == null ? "youtube_shorts" : stringValue(video.getPlatform(), "youtube_shorts").toLowerCase(Locale.ROOT);
        return switch (platform) {
            case "linkedin" -> "4:5";
            case "x" -> "1:1";
            default -> "9:16";
        };
    }

    private List<String> safeZonesFor(CreatorShortVideo video) {
        String platform = video == null ? "youtube_shorts" : stringValue(video.getPlatform(), "youtube_shorts").toLowerCase(Locale.ROOT);
        return switch (platform) {
            case "linkedin" -> List.of("caption_safe_bottom", "profile_safe_top");
            case "x" -> List.of("square_center_safe", "caption_safe_bottom");
            default -> List.of("top_caption_safe", "bottom_ui_safe", "right_action_rail_safe");
        };
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

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> map = mapValue(item);
                if (!map.isEmpty()) {
                    result.add(map);
                }
            }
            return result;
        }
        return new ArrayList<>();
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

    private record RepairOutcome(
            Map<String, Object> candidate,
            Map<String, Object> critics,
            List<Map<String, Object>> repairs
    ) {
    }

    private record CriticResult(
            String stage,
            String status,
            boolean passed,
            double confidence,
            String summary,
            List<String> issues
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("stage", stage);
            map.put("status", status);
            map.put("passed", passed);
            map.put("confidence", confidence);
            map.put("summary", summary);
            map.put("issues", issues);
            map.put("timestamp", OffsetDateTime.now().toString());
            return map;
        }
    }

    public record CandidateCriticRepairResult(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
