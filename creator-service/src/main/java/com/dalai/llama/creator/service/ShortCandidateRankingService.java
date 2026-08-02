package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorShortCandidate;
import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import com.dalai.llama.creator.repository.CreatorShortCandidateRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ShortCandidateRankingService {

    private static final String SOURCE = "deterministic_candidate_ranking_worker";

    private final CreatorShortCandidateRepository candidateRepository;

    public ShortCandidateRankingService(CreatorShortCandidateRepository candidateRepository) {
        this.candidateRepository = candidateRepository;
    }

    public CandidateRankingResult rank(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> candidates
    ) {
        List<Map<String, Object>> ranked = new ArrayList<>();
        List<Map<String, Object>> rankingAudits = new ArrayList<>();
        RankingCalibration calibration = rankingCalibration(video);
        int originalRank = 1;
        for (Map<String, Object> sourceCandidate : copyList(candidates)) {
            Map<String, Object> candidate = new LinkedHashMap<>(sourceCandidate);
            Map<String, Object> audit = rankAudit(video, videoDna, transcript, graph, candidate, originalRank, calibration);
            Map<String, Object> metadata = mapValue(candidate.get("metadata"));
            metadata.put("candidateRanking", audit);
            metadata.put("candidateRankingSource", SOURCE);
            candidate.put("metadata", metadata);
            candidate.put("score", doubleValue(audit.get("finalScore"), doubleValue(candidate.get("score"), 0.0)));
            ranked.add(candidate);
            rankingAudits.add(audit);
            originalRank++;
        }

        ranked.sort(Comparator
                .comparingDouble((Map<String, Object> candidate) -> doubleValue(candidate.get("score"), 0.0))
                .reversed()
                .thenComparing(candidate -> stringValue(candidate.get("title"), "")));

        for (int index = 0; index < ranked.size(); index++) {
            Map<String, Object> candidate = ranked.get(index);
            Map<String, Object> metadata = mapValue(candidate.get("metadata"));
            Map<String, Object> audit = mapValue(metadata.get("candidateRanking"));
            audit.put("finalRank", index + 1);
            metadata.put("candidateRanking", audit);
            candidate.put("metadata", metadata);
            Map<String, Object> renderManifest = mapValue(candidate.get("renderManifest"));
            renderManifest.put("rankedBy", SOURCE);
            renderManifest.put("finalRank", index + 1);
            candidate.put("renderManifest", renderManifest);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", SOURCE);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("candidateCount", ranked.size());
        metadata.put("videoType", stringValue(videoDna == null ? null : videoDna.get("primaryType"), "unknown"));
        metadata.put("graphSource", stringValue(graph == null ? null : graph.get("analysisSource"), "unknown"));
        metadata.put("rankingAudits", rankingAudits);
        metadata.put("rankingCalibration", calibration.metadata());
        metadata.put("weights", Map.of(
                "baseScore", 0.24,
                "storyIntent", 0.15,
                "hook", 0.18,
                "visualComposition", 0.13,
                "criticHealth", 0.16,
                "durationFit", 0.04,
                "reviewFeedback", 0.10
        ));

        List<Map<String, Object>> trace = List.of(traceRow(
                "CANDIDATE_RANKING",
                ranked.isEmpty() ? "WARN" : "COMPLETED",
                ranked.isEmpty()
                        ? "No candidates were available for ranking."
                        : "Ranked candidates by story intent, hook quality, critic health, visual composition, duration fit, and platform readiness.",
                ranked.isEmpty() ? 0.25 : 0.88,
                metadata
        ));
        return new CandidateRankingResult(ranked, trace, metadata);
    }

    private Map<String, Object> rankAudit(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            Map<String, Object> candidate,
            int originalRank,
            RankingCalibration calibration
    ) {
        Map<String, Object> metadata = mapValue(candidate.get("metadata"));
        Map<String, Object> edl = mapValue(candidate.get("editDecisionList"));
        Map<String, Object> renderManifest = mapValue(candidate.get("renderManifest"));
        Map<String, Object> visualComposition = mapValue(firstNonEmpty(metadata.get("visualStoryComposition"), renderManifest.get("visualStoryComposition"), edl.get("visualStoryComposition")));
        Map<String, Object> critics = mapValue(metadata.get("critics"));

        double baseScore = clamp(doubleValue(candidate.get("score"), 75.0), 0.0, 100.0);
        double storyScore = storyScore(metadata, edl);
        double hookScore = hookScore(candidate, metadata);
        double visualScore = clamp(doubleValue(visualComposition.get("visualVarietyScore"), 62.0), 0.0, 100.0);
        double criticScore = criticHealthScore(critics, metadata, renderManifest);
        double durationScore = durationFitScore(video, candidate, edl);
        double feedbackScore = feedbackScore(candidate, calibration);
        double finalScore = baseScore * 0.24
                + storyScore * 0.15
                + hookScore * 0.18
                + visualScore * 0.13
                + criticScore * 0.16
                + durationScore * 0.04
                + feedbackScore * 0.10;

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("source", SOURCE);
        audit.put("originalRank", originalRank);
        audit.put("finalScore", round3(clamp(finalScore, 0.0, 100.0)));
        audit.put("baseScore", round3(baseScore));
        audit.put("storyScore", round3(storyScore));
        audit.put("hookScore", round3(hookScore));
        audit.put("visualCompositionScore", round3(visualScore));
        audit.put("criticHealthScore", round3(criticScore));
        audit.put("durationFitScore", round3(durationScore));
        audit.put("reviewFeedbackScore", round3(feedbackScore));
        audit.put("segmentCount", listOfMaps(edl.get("segments")).size());
        audit.put("transcriptNodeCount", transcript == null ? 0 : transcript.size());
        audit.put("graphBacked", graph != null && !graph.isEmpty());
        audit.put("videoType", stringValue(videoDna == null ? null : videoDna.get("primaryType"), "unknown"));
        audit.put("timestamp", OffsetDateTime.now().toString());
        return audit;
    }

    private double storyScore(Map<String, Object> metadata, Map<String, Object> edl) {
        double score = 50.0;
        if (!mapValue(metadata.get("storyIntent")).isEmpty()) score += 18.0;
        if (!mapValue(metadata.get("storyBeatPlan")).isEmpty()) score += 14.0;
        if (!listOfMaps(edl.get("protectedChains")).isEmpty()) score += 12.0;
        if (!listOfMaps(edl.get("storyBeats")).isEmpty()) score += 8.0;
        if (Boolean.TRUE.equals(metadata.get("requiresGraphCut"))) score += 5.0;
        return clamp(score, 0.0, 100.0);
    }

    private double hookScore(Map<String, Object> candidate, Map<String, Object> metadata) {
        double score = 40.0;
        String hookType = stringValue(candidate.get("hookType"), "");
        String title = stringValue(candidate.get("title"), "");
        Map<String, Object> hookPlan = mapValue(metadata.get("hookPlan"));
        String openingLine = stringValue(hookPlan.get("openingLine"), "");
        String hookPriority = stringValue(hookPlan.get("hookPriority"), "");
        String combined = (hookType + " " + title + " " + openingLine + " " + hookPriority).toLowerCase(Locale.ROOT);
        if (!hookType.isBlank()) score += 12.0;
        if (!title.isBlank() && title.length() <= 110) score += 10.0;
        if (!hookPlan.isEmpty()) score += 18.0;
        if (!openingLine.isBlank()) score += 8.0;
        if (combined.contains("?") || combined.matches(".*\\b(why|how|what if|what|when|where|who)\\b.*")) score += 12.0;
        if (combined.matches(".*\\b(curiosity|strange|mystery|hidden|secret|surprising|impossible|ancient|unexpected)\\b.*")) score += 10.0;
        if (combined.matches(".*\\b(abstract|idea|concept|meaning|pattern|system|principle|biology|evolution)\\b.*")) score += 8.0;
        if ("source hook".equalsIgnoreCase(hookType) && !combined.matches(".*\\b(why|how|what|curiosity|strange|mystery|abstract|idea|concept)\\b.*")) score -= 10.0;
        return clamp(score, 0.0, 100.0);
    }

    private double criticHealthScore(Map<String, Object> critics, Map<String, Object> metadata, Map<String, Object> renderManifest) {
        double score = 100.0;
        score -= criticPenalty(mapValue(critics.get("compressionCritic")));
        score -= criticPenalty(mapValue(critics.get("hookCritic")));
        score -= criticPenalty(mapValue(critics.get("captionCritic")));
        score -= criticPenalty(mapValue(critics.get("visualCritic")));
        score -= criticPenalty(mapValue(critics.get("continuityCritic")));
        score -= statusPenalty(stringValue(metadata.get("visualCriticStatus"), ""));
        score -= statusPenalty(stringValue(metadata.get("continuityCriticStatus"), ""));
        score -= statusPenalty(stringValue(renderManifest.get("visualCriticStatus"), ""));
        score -= statusPenalty(stringValue(renderManifest.get("continuityCriticStatus"), ""));
        return clamp(score, 0.0, 100.0);
    }

    private double durationFitScore(CreatorShortVideo video, Map<String, Object> candidate, Map<String, Object> edl) {
        double target = Math.max(15, video == null || video.getTargetDurationSeconds() == null ? 60 : video.getTargetDurationSeconds());
        double duration = doubleValue(edl.get("actualDurationSeconds"), doubleValue(candidate.get("durationSeconds"), target));
        double delta = Math.abs(target - duration);
        if (duration <= 0.0) return 0.0;
        if (delta <= 2.0) return 100.0;
        if (delta <= 8.0) return 82.0;
        if (duration < target * 0.35) return 40.0;
        return 64.0;
    }

    private double criticPenalty(Map<String, Object> critic) {
        if (critic.isEmpty()) return 4.0;
        String status = stringValue(critic.get("status"), "");
        if ("FAIL".equalsIgnoreCase(status)) return 30.0;
        if ("WARN".equalsIgnoreCase(status)) return 10.0;
        if (Boolean.FALSE.equals(critic.get("passed"))) return 20.0;
        return 0.0;
    }

    private double statusPenalty(String status) {
        if ("FAIL".equalsIgnoreCase(status)) return 24.0;
        if ("WARN".equalsIgnoreCase(status)) return 8.0;
        return 0.0;
    }

    private double feedbackScore(Map<String, Object> candidate, RankingCalibration calibration) {
        if (calibration == null || (calibration.accepted().isEmpty() && calibration.rejected().isEmpty())) {
            return 50.0;
        }
        java.util.Set<String> tokens = signatureTokens(candidate);
        double accepted = calibration.accepted().stream().mapToDouble(signal -> jaccard(tokens, signal.tokens())).max().orElse(0.0);
        double rejected = calibration.rejected().stream().mapToDouble(signal -> jaccard(tokens, signal.tokens())).max().orElse(0.0);
        return clamp(50.0 + accepted * 42.0 - rejected * 46.0, 0.0, 100.0);
    }

    private RankingCalibration rankingCalibration(CreatorShortVideo video) {
        if (video == null || video.getTenantId() == null || video.getTenantId().isBlank()) {
            return new RankingCalibration(List.of(), List.of(), Map.of("source", "none", "reason", "missing_tenant"));
        }
        List<HistoricalSignal> accepted = new ArrayList<>();
        List<HistoricalSignal> rejected = new ArrayList<>();
        candidateRepository.findTop150ByTenantIdAndStatusInOrderByUpdatedAtDesc(video.getTenantId(), List.of("APPROVED"))
                .forEach(candidate -> accepted.add(signal(candidate)));
        candidateRepository.findTop150ByTenantIdAndReviewStatusInOrderByUpdatedAtDesc(video.getTenantId(), List.of("APPROVED", "LOCKED", "PINNED"))
                .forEach(candidate -> accepted.add(signal(candidate)));
        candidateRepository.findTop150ByTenantIdAndStatusInOrderByUpdatedAtDesc(video.getTenantId(), List.of("REJECTED", "RENDER_FAILED", "RENDER_QA_FAILED", "RERENDER_FAILED"))
                .forEach(candidate -> rejected.add(signal(candidate)));
        candidateRepository.findTop150ByTenantIdAndReviewStatusInOrderByUpdatedAtDesc(video.getTenantId(), List.of("REJECTED", "NEEDS_REPAIR"))
                .forEach(candidate -> rejected.add(signal(candidate)));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "creator_short_candidate_review_history");
        metadata.put("acceptedSignalCount", accepted.size());
        metadata.put("rejectedSignalCount", rejected.size());
        metadata.put("maxSignalsPerClass", 300);
        metadata.put("policy", "bounded_similarity_nudge_v1");
        return new RankingCalibration(accepted.stream().limit(300).toList(), rejected.stream().limit(300).toList(), metadata);
    }

    private HistoricalSignal signal(CreatorShortCandidate candidate) {
        return new HistoricalSignal(candidate == null || candidate.getId() == null ? "" : candidate.getId().toString(), signatureTokens(candidate));
    }

    private java.util.Set<String> signatureTokens(CreatorShortCandidate candidate) {
        if (candidate == null) return java.util.Set.of();
        String text = String.join(" ",
                stringValue(candidate.getTitle(), ""),
                stringValue(candidate.getHookType(), ""),
                mapValue(candidate.getMetadata()).toString(),
                mapValue(candidate.getEditDecisionList()).toString()
        );
        return tokenSet(text);
    }

    private java.util.Set<String> signatureTokens(Map<String, Object> candidate) {
        String text = String.join(" ",
                stringValue(candidate.get("title"), ""),
                stringValue(candidate.get("hookType"), ""),
                mapValue(candidate.get("metadata")).toString(),
                mapValue(candidate.get("editDecisionList")).toString()
        );
        return tokenSet(text);
    }

    private java.util.Set<String> tokenSet(String text) {
        java.util.Set<String> tokens = new HashSet<>();
        for (String token : stringValue(text, "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (token.length() >= 4 && !List.of("this", "that", "with", "from", "have", "will", "your", "about", "into", "there", "their", "candidate", "metadata", "segment").contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private double jaccard(java.util.Set<String> left, java.util.Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return 0.0;
        int overlap = 0;
        for (String token : left) {
            if (right.contains(token)) overlap++;
        }
        int union = left.size() + right.size() - overlap;
        return union <= 0 ? 0.0 : overlap / (double) union;
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

    private Object firstNonEmpty(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value instanceof Map<?, ?> map && !map.isEmpty()) return value;
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> value) {
        if (value == null) return new ArrayList<>();
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record RankingCalibration(
            List<HistoricalSignal> accepted,
            List<HistoricalSignal> rejected,
            Map<String, Object> metadata
    ) {
    }

    private record HistoricalSignal(
            String candidateId,
            java.util.Set<String> tokens
    ) {
    }

    public record CandidateRankingResult(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
