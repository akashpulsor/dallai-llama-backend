package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ShortGlobalCriticService {

    private static final String SOURCE = "deterministic_global_critic_worker";

    public GlobalCriticResult critique(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> candidatePayloads,
            Map<String, Object> upstreamEvidence
    ) {
        List<Map<String, Object>> candidates = copyList(candidatePayloads);
        if (candidates.isEmpty()) {
            List<Map<String, Object>> trace = List.of(traceRow(
                    "GLOBAL_CRITIC",
                    "WARN",
                    "Global critic could not run because no candidates were available.",
                    0.25,
                    Map.of("source", SOURCE)
            ));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", SOURCE);
            metadata.put("generatedAt", OffsetDateTime.now().toString());
            metadata.put("candidateCount", 0);
            metadata.put("passedCount", 0);
            metadata.put("blockedCount", 0);
            return new GlobalCriticResult(candidates, trace, metadata);
        }

        List<Map<String, Object>> reviewed = new ArrayList<>();
        List<Map<String, Object>> audits = new ArrayList<>();
        List<Map<String, Object>> repairs = new ArrayList<>();
        int passedCount = 0;
        int warningCount = 0;
        int blockedCount = 0;
        int renderReadyCount = 0;
        for (int index = 0; index < candidates.size(); index++) {
            AuditOutcome outcome = auditCandidate(
                    video,
                    videoDna,
                    transcript,
                    graph,
                    candidates.get(index),
                    upstreamEvidence,
                    index + 1
            );
            reviewed.add(outcome.candidate());
            audits.add(outcome.audit());
            repairs.addAll(outcome.repairs());
            String status = stringValue(outcome.audit().get("status"), "WARN");
            if ("PASS".equals(status)) {
                passedCount++;
                renderReadyCount++;
            } else if ("FAIL".equals(status)) {
                blockedCount++;
            } else {
                warningCount++;
                if (Boolean.TRUE.equals(outcome.audit().get("renderAllowed"))) {
                    renderReadyCount++;
                }
            }
        }

        String status = blockedCount > 0
                ? "FAILED"
                : (warningCount > 0 || !repairs.isEmpty() ? "COMPLETED_WITH_WARNINGS" : "COMPLETED");
        double confidence = blockedCount > 0 ? 0.55 : warningCount > 0 ? 0.82 : 0.94;

        Map<String, Object> traceMetadata = new LinkedHashMap<>();
        traceMetadata.put("source", SOURCE);
        traceMetadata.put("candidateCount", reviewed.size());
        traceMetadata.put("passedCount", passedCount);
        traceMetadata.put("warningCount", warningCount);
        traceMetadata.put("blockedCount", blockedCount);
        traceMetadata.put("renderReadyCount", renderReadyCount);
        traceMetadata.put("repairCount", repairs.size());
        traceMetadata.put("upstreamStages", upstreamStages(upstreamEvidence));

        List<Map<String, Object>> trace = List.of(traceRow(
                "GLOBAL_CRITIC",
                status,
                blockedCount > 0
                        ? "Global critic blocked candidates with unresolved production-readiness failures before rendering."
                        : warningCount > 0 || !repairs.isEmpty()
                        ? "Global critic verified all candidates and marked review warnings before rendering."
                        : "Global critic verified every candidate is render-ready across compression, hooks, captions, visual plan, continuity, and metadata.",
                confidence,
                traceMetadata
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", SOURCE);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("candidateCount", reviewed.size());
        metadata.put("passedCount", passedCount);
        metadata.put("warningCount", warningCount);
        metadata.put("blockedCount", blockedCount);
        metadata.put("renderReadyCount", renderReadyCount);
        metadata.put("repairCount", repairs.size());
        metadata.put("audits", audits);
        metadata.put("repairs", repairs);
        metadata.put("upstreamEvidence", upstreamSummary(upstreamEvidence));

        return new GlobalCriticResult(reviewed, trace, metadata);
    }

    private AuditOutcome auditCandidate(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            Map<String, Object> candidatePayload,
            Map<String, Object> upstreamEvidence,
            int rank
    ) {
        Map<String, Object> candidate = new LinkedHashMap<>(candidatePayload);
        Map<String, Object> metadata = mapValue(candidate.get("metadata"));
        Map<String, Object> edl = mapValue(candidate.get("editDecisionList"));
        Map<String, Object> captionPlan = mapValue(candidate.get("captionPlan"));
        Map<String, Object> renderManifest = mapValue(candidate.get("renderManifest"));
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> repairs = new ArrayList<>();
        Set<String> gates = new LinkedHashSet<>();

        List<Map<String, Object>> segments = listOfMaps(edl.get("segments"));
        List<Map<String, Object>> captions = listOfMaps(captionPlan.get("captions"));
        double edlDuration = doubleValue(edl.get("actualDurationSeconds"), durationFromSegments(segments));
        double manifestDuration = doubleValue(renderManifest.get("actualDurationSeconds"), edlDuration);
        int targetDuration = targetDuration(video);

        require(!segments.isEmpty(), issues, "high", "NO_EDL_SEGMENTS", "Candidate has no exact renderable EDL segments.", gates, "compression");
        require(Boolean.TRUE.equals(edl.get("exactTimestampEdl")), issues, "high", "EDL_NOT_EXACT", "Candidate EDL is not marked as exact timestamp.", gates, "compression");
        require(edlDuration > 0.0, issues, "high", "INVALID_DURATION", "Candidate EDL duration is invalid.", gates, "compression");
        require(edlDuration <= targetDuration + 2.0, issues, "medium", "DURATION_OVER_TARGET", "Candidate duration exceeds target by more than two seconds.", gates, "compression");
        require(!listOfMaps(edl.get("protectedChains")).isEmpty(), issues, "medium", "NO_PROTECTED_CHAIN", "Candidate is missing protected story-chain metadata.", gates, "story");

        require(!captions.isEmpty(), issues, "medium", "NO_CAPTIONS", "Candidate has no caption plan.", gates, "captions");
        require(captionCoverage(captions, edlDuration) >= 0.45 || captions.size() >= Math.min(2, segments.size()), issues, "medium", "LOW_CAPTION_COVERAGE", "Caption coverage is too sparse for review/render readiness.", gates, "captions");
        require(Math.abs(manifestDuration - edlDuration) <= 0.15, issues, "medium", "MANIFEST_DURATION_MISMATCH", "Render manifest duration does not match final EDL duration.", gates, "render");

        Map<String, Object> visualPlan = mapValue(renderManifest.get("visualEnhancementPlan"));
        require(!visualPlan.isEmpty(), issues, "high", "NO_VISUAL_PLAN", "Candidate has no visual enhancement plan.", gates, "visual");
        require(listOfMaps(visualPlan.get("segmentPlans")).size() == segments.size(), issues, "medium", "VISUAL_SEGMENT_MISMATCH", "Visual segment plan count does not match EDL segment count.", gates, "visual");

        Map<String, Object> critics = mapValue(metadata.get("critics"));
        collectCriticIssues("compressionCritic", mapValue(critics.get("compressionCritic")), issues, gates, "compression");
        collectCriticIssues("hookCritic", mapValue(critics.get("hookCritic")), issues, gates, "hook");
        collectCriticIssues("captionCritic", mapValue(critics.get("captionCritic")), issues, gates, "captions");
        collectCriticIssues("visualCritic", firstCritic(metadata, renderManifest, "visualCritic"), issues, gates, "visual");
        collectCriticIssues("continuityCritic", firstCritic(metadata, renderManifest, "continuityCritic"), issues, gates, "continuity");

        require(!stringValue(candidate.get("title"), "").isBlank(), issues, "medium", "MISSING_TITLE", "Candidate title is missing.", gates, "hook");
        require(!stringValue(candidate.get("hookType"), "").isBlank(), issues, "medium", "MISSING_HOOK_TYPE", "Candidate hook type is missing.", gates, "hook");
        require(!transcript.isEmpty(), issues, "medium", "NO_TRANSCRIPT_EVIDENCE", "Pipeline transcript evidence is missing.", gates, "transcript");
        require(!graph.isEmpty(), issues, "medium", "NO_GRAPH_EVIDENCE", "Pipeline graph evidence is missing.", gates, "graph");

        Map<String, Object> upstreamReadiness = upstreamReadiness(upstreamEvidence);
        if ("FAILED".equalsIgnoreCase(stringValue(upstreamReadiness.get("status"), ""))) {
            addIssue(issues, "high", "UPSTREAM_STAGE_FAILED", "An upstream production critic reported a failed status.", gates, "pipeline");
        }

        if (doubleValue(candidate.get("score"), 0.0) <= 0.0) {
            candidate.put("score", Math.max(1, 100 - rank));
            addRepair(repairs, "SCORE_REPAIRED", "Restored missing candidate score for ranking/readiness.");
        }
        if (Math.abs(manifestDuration - edlDuration) > 0.15 && edlDuration > 0.0) {
            renderManifest.put("actualDurationSeconds", round3(edlDuration));
            addRepair(repairs, "MANIFEST_DURATION_REPAIRED", "Aligned render manifest duration with final EDL.");
        }
        if (renderManifest.get("sourceSegments") == null || listOfMaps(renderManifest.get("sourceSegments")).size() != segments.size()) {
            renderManifest.put("sourceSegments", segments);
            addRepair(repairs, "MANIFEST_SOURCE_SEGMENTS_REPAIRED", "Aligned render manifest source segments with final EDL.");
        }
        renderManifest.putIfAbsent("renderPolicy", stringValue(edl.get("renderPolicy"), "concat_source_segments_in_timeline_order"));

        boolean hasUnresolvedHigh = hasSeverity(issues, "high");
        boolean hasWarnings = !issues.isEmpty();
        String status = hasUnresolvedHigh ? "FAIL" : (hasWarnings || !repairs.isEmpty() ? "WARN" : "PASS");
        boolean renderAllowed = !hasUnresolvedHigh;
        String globalReadiness = hasUnresolvedHigh ? "BLOCKED_FOR_REVIEW" : (hasWarnings ? "READY_WITH_REVIEW_WARNINGS" : "READY_FOR_RENDER");

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("source", SOURCE);
        audit.put("status", status);
        audit.put("passed", !"FAIL".equals(status));
        audit.put("renderAllowed", renderAllowed);
        audit.put("globalReadiness", globalReadiness);
        audit.put("confidence", "PASS".equals(status) ? 0.94 : ("WARN".equals(status) ? 0.78 : 0.46));
        audit.put("candidateRank", rank);
        audit.put("candidateTitle", stringValue(candidate.get("title"), ""));
        audit.put("score", candidate.get("score"));
        audit.put("durationSeconds", round3(edlDuration));
        audit.put("targetDurationSeconds", targetDuration);
        audit.put("gates", new ArrayList<>(gates));
        audit.put("issueCount", issues.size());
        audit.put("repairCount", repairs.size());
        audit.put("issues", issues);
        audit.put("repairs", repairs);
        audit.put("upstreamReadiness", upstreamReadiness);
        audit.put("checkedDimensions", List.of(
                "transcript_and_graph_evidence",
                "compression_edl_integrity",
                "story_chain_preservation",
                "hook_readiness",
                "caption_readiness",
                "visual_render_plan_readiness",
                "continuity_readiness",
                "render_manifest_alignment",
                "candidate_metadata_completeness"
        ));
        audit.put("timestamp", OffsetDateTime.now().toString());

        renderManifest.put("globalCritic", audit);
        renderManifest.put("globalCriticStatus", status);
        renderManifest.put("globalReadiness", globalReadiness);
        renderManifest.put("renderBlocked", !renderAllowed);
        renderManifest.put("renderStatus", renderAllowed
                ? stringValue(renderManifest.get("renderStatus"), "READY_TO_RENDER")
                : "BLOCKED_BY_GLOBAL_CRITIC");
        renderManifest.put("requiresHumanReview", Boolean.TRUE.equals(renderManifest.get("requiresHumanReview")) || !"PASS".equals(status));

        critics.put("productionGlobalCritic", audit);
        metadata.put("critics", critics);
        metadata.put("globalCritic", audit);
        metadata.put("globalCriticStatus", status);
        metadata.put("globalCriticSource", SOURCE);
        metadata.put("globalReadiness", globalReadiness);
        metadata.put("globalRenderAllowed", renderAllowed);
        metadata.put("globalCriticIssueCount", issues.size());
        metadata.put("globalCriticRepairCount", repairs.size());
        metadata.put("repairStatus", repairs.isEmpty() ? stringValue(metadata.get("repairStatus"), "NO_REPAIR_NEEDED") : "REPAIRED");

        candidate.put("renderManifest", renderManifest);
        candidate.put("metadata", metadata);
        return new AuditOutcome(candidate, audit, repairs);
    }

    private void collectCriticIssues(String label, Map<String, Object> critic, List<Map<String, Object>> issues, Set<String> gates, String gate) {
        if (critic.isEmpty()) {
            addIssue(issues, "medium", label.toUpperCase(Locale.ROOT) + "_MISSING", label + " audit is missing.", gates, gate);
            return;
        }
        String status = stringValue(critic.get("status"), "");
        boolean passed = Boolean.TRUE.equals(critic.get("passed"))
                || "PASS".equalsIgnoreCase(status)
                || "COMPLETED".equalsIgnoreCase(status);
        if (!passed) {
            String severity = "FAIL".equalsIgnoreCase(status) ? "high" : "medium";
            addIssue(issues, severity, label.toUpperCase(Locale.ROOT) + "_NOT_PASSING", label + " did not pass.", gates, gate);
        }
        for (Map<String, Object> issue : listOfMaps(critic.get("issues"))) {
            String severity = stringValue(issue.get("severity"), "medium");
            boolean repaired = Boolean.TRUE.equals(issue.get("repaired"));
            if ("high".equalsIgnoreCase(severity) && !repaired) {
                addIssue(issues, "high", label.toUpperCase(Locale.ROOT) + "_UNRESOLVED_HIGH", label + " has unresolved high-severity issue: " + stringValue(issue.get("code"), stringValue(issue.get("summary"), "unknown")), gates, gate);
            }
        }
    }

    private Map<String, Object> firstCritic(Map<String, Object> metadata, Map<String, Object> renderManifest, String key) {
        Map<String, Object> fromMetadata = mapValue(metadata.get(key));
        if (!fromMetadata.isEmpty()) {
            return fromMetadata;
        }
        return mapValue(renderManifest.get(key));
    }

    private Map<String, Object> upstreamReadiness(Map<String, Object> upstreamEvidence) {
        Map<String, Object> readiness = new LinkedHashMap<>();
        int failed = 0;
        int warning = 0;
        for (String key : upstreamEvidence.keySet()) {
            Map<String, Object> stage = mapValue(upstreamEvidence.get(key));
            String status = stringValue(stage.get("status"), "");
            int failedCount = intValue(stage.get("failedCount"), 0);
            int blockedCount = intValue(stage.get("blockedCount"), 0);
            int warningCount = intValue(stage.get("warningCount"), 0);
            if ("FAILED".equalsIgnoreCase(status) || failedCount > 0 || blockedCount > 0) {
                failed++;
            } else if (status.toUpperCase(Locale.ROOT).contains("WARN") || warningCount > 0) {
                warning++;
            }
        }
        readiness.put("failedStageCount", failed);
        readiness.put("warningStageCount", warning);
        readiness.put("status", failed > 0 ? "FAILED" : (warning > 0 ? "WARN" : "PASS"));
        return readiness;
    }

    private Map<String, Object> upstreamSummary(Map<String, Object> upstreamEvidence) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : upstreamEvidence.entrySet()) {
            Map<String, Object> value = mapValue(entry.getValue());
            if (value.isEmpty()) {
                continue;
            }
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("source", value.get("source"));
            stage.put("status", value.get("status"));
            stage.put("candidateCount", value.get("candidateCount"));
            stage.put("passedCount", value.get("passedCount"));
            stage.put("warningCount", value.get("warningCount"));
            stage.put("failedCount", value.get("failedCount"));
            stage.put("blockedCount", value.get("blockedCount"));
            stage.put("repairCount", value.get("repairCount"));
            summary.put(entry.getKey(), stage);
        }
        return summary;
    }

    private List<String> upstreamStages(Map<String, Object> upstreamEvidence) {
        return new ArrayList<>(upstreamEvidence.keySet());
    }

    private void require(boolean condition, List<Map<String, Object>> issues, String severity, String code, String summary, Set<String> gates, String gate) {
        if (!condition) {
            addIssue(issues, severity, code, summary, gates, gate);
        }
    }

    private void addIssue(List<Map<String, Object>> issues, String severity, String code, String summary, Set<String> gates, String gate) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", severity);
        issue.put("code", code);
        issue.put("summary", summary);
        issue.put("gate", gate);
        issue.put("createdAt", OffsetDateTime.now().toString());
        issues.add(issue);
        if (gate != null && !gate.isBlank()) {
            gates.add(gate);
        }
    }

    private void addRepair(List<Map<String, Object>> repairs, String code, String summary) {
        Map<String, Object> repair = new LinkedHashMap<>();
        repair.put("code", code);
        repair.put("summary", summary);
        repair.put("createdAt", OffsetDateTime.now().toString());
        repairs.add(repair);
    }

    private boolean hasSeverity(List<Map<String, Object>> issues, String severity) {
        for (Map<String, Object> issue : issues) {
            if (severity.equalsIgnoreCase(stringValue(issue.get("severity"), ""))) {
                return true;
            }
        }
        return false;
    }

    private double captionCoverage(List<Map<String, Object>> captions, double duration) {
        if (captions == null || captions.isEmpty() || duration <= 0.0) {
            return 0.0;
        }
        double covered = 0.0;
        for (Map<String, Object> caption : captions) {
            double start = doubleValue(caption.get("start"), 0.0);
            double end = doubleValue(caption.get("end"), start);
            covered += Math.max(0.0, end - start);
        }
        return Math.max(0.0, Math.min(1.0, covered / duration));
    }

    private double durationFromSegments(List<Map<String, Object>> segments) {
        double duration = 0.0;
        for (Map<String, Object> segment : segments) {
            duration += Math.max(0.0, doubleValue(segment.get("durationSeconds"), doubleValue(segment.get("timelineEnd"), 0.0) - doubleValue(segment.get("timelineStart"), 0.0)));
        }
        return duration;
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

    private record AuditOutcome(
            Map<String, Object> candidate,
            Map<String, Object> audit,
            List<Map<String, Object>> repairs
    ) {
    }

    public record GlobalCriticResult(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
