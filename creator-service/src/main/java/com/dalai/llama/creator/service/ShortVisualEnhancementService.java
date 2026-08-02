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
public class ShortVisualEnhancementService {

    private static final String SOURCE = "deterministic_visual_enhancement_worker";

    public VisualEnhancementResult enhance(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            List<Map<String, Object>> candidatePayloads,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            Map<String, Object> sceneCritic
    ) {
        List<Map<String, Object>> candidates = copyList(candidatePayloads);
        List<Map<String, Object>> safeScenes = copyList(scenes);
        List<Map<String, Object>> safeFrames = copyList(frames);
        Map<String, Map<String, Object>> scenesById = indexById(safeScenes);

        if (candidates.isEmpty()) {
            List<Map<String, Object>> trace = List.of(traceRow(
                    "VISUAL_ENHANCEMENT",
                    "WARN",
                    "Visual enhancement could not run because no repaired short candidates were available.",
                    0.25,
                    Map.of("source", SOURCE)
            ));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", SOURCE);
            metadata.put("generatedAt", OffsetDateTime.now().toString());
            metadata.put("candidateCount", 0);
            metadata.put("planCount", 0);
            return new VisualEnhancementResult(candidates, List.of(), trace, metadata);
        }

        List<Map<String, Object>> enhancedCandidates = new ArrayList<>();
        List<Map<String, Object>> plans = new ArrayList<>();
        Set<String> profiles = new LinkedHashSet<>();
        Set<String> riskFlags = new LinkedHashSet<>();
        int candidateIndex = 0;
        for (Map<String, Object> candidatePayload : candidates) {
            candidateIndex++;
            Map<String, Object> candidate = new LinkedHashMap<>(candidatePayload);
            EnhancementPlan plan = buildPlan(
                    video,
                    videoDna,
                    transcript,
                    graph,
                    candidate,
                    safeScenes,
                    safeFrames,
                    scenesById,
                    sceneCritic,
                    candidateIndex
            );

            Map<String, Object> renderManifest = mapValue(candidate.get("renderManifest"));
            renderManifest.put("visualEnhancementStatus", "READY_FOR_RENDER");
            renderManifest.put("visualEnhancementSource", SOURCE);
            renderManifest.put("visualEnhancementPlan", plan.plan());
            renderManifest.put("targetWidth", plan.target().width());
            renderManifest.put("targetHeight", plan.target().height());
            renderManifest.put("aspectRatio", plan.aspectRatio());
            renderManifest.put("safeZones", plan.plan().getOrDefault("safeZones", safeZonesFor(video)));
            candidate.put("renderManifest", renderManifest);

            Map<String, Object> metadata = mapValue(candidate.get("metadata"));
            metadata.put("visualEnhancementSource", SOURCE);
            metadata.put("visualEnhancementProfile", plan.profile().name());
            metadata.put("visualEnhancementStatus", "READY_FOR_RENDER");
            metadata.put("visualEnhancementPlan", plan.plan());
            metadata.put("visualEnhancementRiskFlags", plan.riskFlags());
            candidate.put("metadata", metadata);

            profiles.add(plan.profile().name());
            riskFlags.addAll(plan.riskFlags());
            plans.add(plan.plan());
            enhancedCandidates.add(candidate);
        }

        boolean sceneBacked = !safeScenes.isEmpty() && !safeFrames.isEmpty();
        String status = sceneBacked ? "COMPLETED" : "COMPLETED_WITH_WARNINGS";
        Map<String, Object> traceMetadata = new LinkedHashMap<>();
        traceMetadata.put("source", SOURCE);
        traceMetadata.put("candidateCount", enhancedCandidates.size());
        traceMetadata.put("planCount", plans.size());
        traceMetadata.put("sceneCount", safeScenes.size());
        traceMetadata.put("frameCount", safeFrames.size());
        traceMetadata.put("profiles", new ArrayList<>(profiles));
        traceMetadata.put("riskFlags", new ArrayList<>(riskFlags));
        traceMetadata.put("rendererContract", "ffmpeg_scale_crop_pad_eq_hqdn3d_unsharp_ass_captions");

        List<Map<String, Object>> trace = List.of(traceRow(
                "VISUAL_ENHANCEMENT",
                status,
                sceneBacked
                        ? "Built renderable FFmpeg visual enhancement plans from scene frames, scene critic output, EDL segments, platform safe zones, and captions."
                        : "Built renderable FFmpeg visual enhancement plans from candidates and platform rules; scene evidence was incomplete.",
                sceneBacked ? 0.88 : 0.68,
                traceMetadata
        ));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", SOURCE);
        metadata.put("generatedAt", OffsetDateTime.now().toString());
        metadata.put("candidateCount", enhancedCandidates.size());
        metadata.put("planCount", plans.size());
        metadata.put("sceneBacked", sceneBacked);
        metadata.put("sceneCount", safeScenes.size());
        metadata.put("frameCount", safeFrames.size());
        metadata.put("profiles", new ArrayList<>(profiles));
        metadata.put("riskFlags", new ArrayList<>(riskFlags));
        metadata.put("plans", plans);

        return new VisualEnhancementResult(enhancedCandidates, plans, trace, metadata);
    }

    private EnhancementPlan buildPlan(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> transcript,
            Map<String, Object> graph,
            Map<String, Object> candidate,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            Map<String, Map<String, Object>> scenesById,
            Map<String, Object> sceneCritic,
            int rank
    ) {
        Map<String, Object> renderManifest = mapValue(candidate.get("renderManifest"));
        Map<String, Object> edl = mapValue(candidate.get("editDecisionList"));
        List<Map<String, Object>> segments = listOfMaps(edl.get("segments"));
        String aspectRatio = stringValue(renderManifest.get("aspectRatio"), aspectRatioFor(video));
        Dimensions target = targetDimensions(aspectRatio);
        String signalText = signalText(video, videoDna, graph, candidate, scenes, sceneCritic);
        List<String> risks = riskFlags(signalText, segments, scenesById);
        VisualProfile profile = chooseProfile(signalText, risks);
        String layoutMode = layoutMode(signalText, profile);

        List<Map<String, Object>> segmentPlans = new ArrayList<>();
        Set<String> coveredSceneIds = new LinkedHashSet<>();
        int index = 0;
        for (Map<String, Object> segment : segments) {
            String sceneId = sceneIdFor(segment, scenes, scenesById);
            if (!sceneId.isBlank()) {
                coveredSceneIds.add(sceneId);
            }
            Map<String, Object> segmentPlan = new LinkedHashMap<>();
            segmentPlan.put("index", index);
            segmentPlan.put("nodeId", stringValue(segment.get("nodeId"), ""));
            segmentPlan.put("sceneId", sceneId);
            segmentPlan.put("sourceStart", round3(doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0))));
            segmentPlan.put("sourceEnd", round3(doubleValue(segment.get("sourceEnd"), doubleValue(segment.get("end"), 0.0))));
            segmentPlan.put("timelineStart", round3(doubleValue(segment.get("timelineStart"), 0.0)));
            segmentPlan.put("timelineEnd", round3(doubleValue(segment.get("timelineEnd"), 0.0)));
            segmentPlan.put("filterProfile", profile.name());
            segmentPlan.put("layoutMode", layoutMode);
            segmentPlan.put("cropMode", "fit_with_pad".equals(layoutMode) ? "preserve_full_frame" : "center_safe_crop");
            segmentPlan.put("cropAnchor", "center");
            segmentPlan.put("riskFlags", risksForSegment(segment, risks, scenesById));
            segmentPlan.put("frameIds", frameIdsForSegment(segment, sceneId, frames));
            segmentPlans.add(segmentPlan);
            index++;
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("source", SOURCE);
        plan.put("version", 1);
        plan.put("generatedAt", OffsetDateTime.now().toString());
        plan.put("status", "READY_FOR_RENDER");
        plan.put("rankIndex", rank);
        plan.put("candidateTitle", stringValue(candidate.get("title"), ""));
        plan.put("aspectRatio", aspectRatio);
        plan.put("targetWidth", target.width());
        plan.put("targetHeight", target.height());
        plan.put("layoutMode", layoutMode);
        plan.put("scaleMode", "fit_with_pad".equals(layoutMode) ? "fit" : "fill");
        plan.put("cropMode", "fit_with_pad".equals(layoutMode) ? "preserve_full_frame" : "center_safe_crop");
        plan.put("filterProfile", profile.name());
        plan.put("enhancements", profile.toMap());
        plan.put("captionStyle", captionStyle(video, target, profile));
        plan.put("safeZones", safeZonesFor(video));
        plan.put("segmentPlans", segmentPlans);
        plan.put("riskFlags", risks);
        plan.put("sceneCoverage", sceneCoverage(segments.size(), coveredSceneIds, scenes));
        plan.put("renderInstructions", List.of(
                "normalize_every_edl_segment_to_target_dimensions",
                "apply_layout_scale_crop_or_pad",
                "apply_denoise_eq_and_sharpen_filters",
                "burn_ass_captions_inside_platform_safe_zone",
                "upload_rendered_mp4_to_minio"
        ));
        plan.put("rendererContract", "ShortRenderingService.visualEnhancementPlan.v1");
        plan.put("transcriptNodeCount", transcript == null ? 0 : transcript.size());

        return new EnhancementPlan(plan, profile, target, aspectRatio, risks);
    }

    private VisualProfile chooseProfile(String signalText, List<String> risks) {
        boolean screen = containsAny(signalText, "screen", "screencast", "presentation", "slide", "dashboard", "code", "tutorial");
        boolean lowLight = risks.contains("LOW_LIGHT") || containsAny(signalText, "low light", "dark", "underexposed", "night");
        boolean noisy = risks.contains("NOISY_SOURCE") || containsAny(signalText, "noise", "grain", "compression artifact", "pixelated");
        boolean motion = risks.contains("MOTION_BLUR") || containsAny(signalText, "motion blur", "shaky", "fast movement");
        boolean talkingHead = containsAny(signalText, "talking head", "interview", "podcast", "conversation", "speaker", "face");

        if (screen) {
            return new VisualProfile("screen_content_clean", 0.0, 1.02, 1.0, 1.0, false, 0.0, 0.0, 0.0, 0.0, true, 0.62, false);
        }
        if (lowLight) {
            return new VisualProfile("low_light_scene_safe", 0.025, 1.08, 1.06, 1.04, true, 1.6, 1.2, 4.8, 3.6, true, 0.35, false);
        }
        if (noisy || motion) {
            return new VisualProfile("noisy_mobile_clean", 0.008, 1.05, 1.05, 1.0, true, 1.4, 1.1, 4.2, 3.2, true, 0.30, false);
        }
        if (talkingHead) {
            return new VisualProfile("talking_head_social", 0.004, 1.04, 1.07, 1.0, true, 1.0, 0.8, 3.2, 2.4, true, 0.42, false);
        }
        return new VisualProfile("balanced_social", 0.0, 1.04, 1.08, 1.0, true, 0.8, 0.6, 2.8, 2.1, true, 0.45, false);
    }

    private String layoutMode(String signalText, VisualProfile profile) {
        if ("screen_content_clean".equals(profile.name()) || containsAny(signalText, "slides", "presentation", "dashboard", "whiteboard", "screen share")) {
            return "fit_with_pad";
        }
        return "fill_crop";
    }

    private Map<String, Object> captionStyle(CreatorShortVideo video, Dimensions target, VisualProfile profile) {
        int fontSize = Math.max(42, Math.min(78, target.height() / 26));
        if ("screen_content_clean".equals(profile.name())) {
            fontSize = Math.max(38, Math.min(fontSize, 64));
        }
        int bottomMargin = Math.max(120, target.height() / 9);
        Map<String, Object> style = new LinkedHashMap<>();
        style.put("source", SOURCE);
        style.put("fontName", "Noto Sans");
        style.put("fontSize", fontSize);
        style.put("bold", true);
        style.put("primaryColor", "&H00FFFFFF");
        style.put("outlineColor", "&H90000000");
        style.put("backColor", "&H80000000");
        style.put("outline", "screen_content_clean".equals(profile.name()) ? 3.5 : 4.0);
        style.put("shadow", 2.0);
        style.put("alignment", 2);
        style.put("leftMargin", Math.max(70, target.width() / 14));
        style.put("rightMargin", Math.max(70, target.width() / 14));
        style.put("bottomMargin", bottomMargin);
        style.put("maxCharsPerLine", "linkedin".equalsIgnoreCase(video == null ? "" : stringValue(video.getPlatform(), "")) ? 32 : 28);
        style.put("safeZone", "bottom_ui_safe");
        return style;
    }

    private List<String> riskFlags(String signalText, List<Map<String, Object>> segments, Map<String, Map<String, Object>> scenesById) {
        Set<String> flags = new LinkedHashSet<>();
        if (containsAny(signalText, "low light", "underexposed", "too dark", "night")) {
            flags.add("LOW_LIGHT");
        }
        if (containsAny(signalText, "overexposed", "washed out", "glare", "bright highlight")) {
            flags.add("OVEREXPOSED");
        }
        if (containsAny(signalText, "noise", "grain", "artifact", "pixelated", "compression")) {
            flags.add("NOISY_SOURCE");
        }
        if (containsAny(signalText, "motion blur", "shaky", "camera shake", "fast movement")) {
            flags.add("MOTION_BLUR");
        }
        if (containsAny(signalText, "screen", "presentation", "slide", "dashboard", "code")) {
            flags.add("PRESERVE_FULL_FRAME");
        }
        if (segments == null || segments.isEmpty()) {
            flags.add("NO_SEGMENT_EVIDENCE");
        }
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            String sceneId = stringValue(segment.get("sceneId"), "");
            if (!sceneId.isBlank() && !scenesById.containsKey(sceneId)) {
                flags.add("SCENE_REFERENCE_MISSING");
            }
        }
        return new ArrayList<>(flags);
    }

    private List<String> risksForSegment(Map<String, Object> segment, List<String> globalRisks, Map<String, Map<String, Object>> scenesById) {
        Set<String> risks = new LinkedHashSet<>(globalRisks);
        String text = (stringValue(segment.get("transcript"), "") + " " + mapValue(scenesById.get(stringValue(segment.get("sceneId"), ""))).toString()).toLowerCase(Locale.ROOT);
        if (!containsAny(text, "screen", "presentation", "slide", "dashboard", "code")) {
            risks.remove("PRESERVE_FULL_FRAME");
        }
        return new ArrayList<>(risks);
    }

    private Map<String, Object> sceneCoverage(int segmentCount, Set<String> coveredSceneIds, List<Map<String, Object>> scenes) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("segmentCount", segmentCount);
        coverage.put("coveredSceneCount", coveredSceneIds.size());
        coverage.put("availableSceneCount", scenes == null ? 0 : scenes.size());
        coverage.put("coveredSceneIds", new ArrayList<>(coveredSceneIds));
        coverage.put("coverageRatio", scenes == null || scenes.isEmpty() ? 0.0 : round3(coveredSceneIds.size() / (double) scenes.size()));
        return coverage;
    }

    private String sceneIdFor(Map<String, Object> segment, List<Map<String, Object>> scenes, Map<String, Map<String, Object>> scenesById) {
        String existing = stringValue(segment.get("sceneId"), "");
        if (!existing.isBlank() && scenesById.containsKey(existing)) {
            return existing;
        }
        double start = doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0));
        double end = doubleValue(segment.get("sourceEnd"), doubleValue(segment.get("end"), start));
        double midpoint = start + Math.max(0.0, end - start) / 2.0;
        for (Map<String, Object> scene : scenes == null ? List.<Map<String, Object>>of() : scenes) {
            double sceneStart = doubleValue(scene.get("start"), 0.0);
            double sceneEnd = doubleValue(scene.get("end"), sceneStart);
            if (midpoint >= sceneStart && midpoint <= sceneEnd) {
                return stringValue(scene.get("id"), "");
            }
        }
        return existing;
    }

    private List<String> frameIdsForSegment(Map<String, Object> segment, String sceneId, List<Map<String, Object>> frames) {
        Set<String> ids = new LinkedHashSet<>();
        Object segmentFrames = segment.get("frames");
        if (segmentFrames instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    ids.add(String.valueOf(item));
                }
            }
        }
        if (ids.isEmpty()) {
            for (Map<String, Object> frame : frames == null ? List.<Map<String, Object>>of() : frames) {
                if (sceneId.equals(stringValue(frame.get("sceneId"), ""))) {
                    ids.add(stringValue(frame.get("id"), ""));
                }
            }
        }
        ids.remove("");
        return new ArrayList<>(ids);
    }

    private Map<String, Map<String, Object>> indexById(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> item : items == null ? List.<Map<String, Object>>of() : items) {
            String id = stringValue(item.get("id"), "");
            if (!id.isBlank()) {
                result.put(id, item);
            }
        }
        return result;
    }

    private String signalText(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            Map<String, Object> graph,
            Map<String, Object> candidate,
            List<Map<String, Object>> scenes,
            Map<String, Object> sceneCritic
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append(video == null ? "" : stringValue(video.getPlatform(), "")).append(' ');
        builder.append(video == null ? "" : stringValue(video.getTitle(), "")).append(' ');
        builder.append(videoDna == null ? "" : videoDna).append(' ');
        builder.append(graph == null ? "" : mapValue(graph).getOrDefault("summary", "")).append(' ');
        builder.append(stringValue(candidate.get("title"), "")).append(' ');
        builder.append(mapValue(candidate.get("editDecisionList")).getOrDefault("strategy", "")).append(' ');
        builder.append(sceneCritic == null ? "" : sceneCritic).append(' ');
        for (Map<String, Object> scene : scenes == null ? List.<Map<String, Object>>of() : scenes) {
            builder.append(stringValue(scene.get("label"), "")).append(' ');
            builder.append(stringValue(scene.get("source"), "")).append(' ');
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private Dimensions targetDimensions(String aspectRatio) {
        String normalized = stringValue(aspectRatio, "9:16").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "16:9", "horizontal", "landscape" -> new Dimensions(1920, 1080);
            case "4:5" -> new Dimensions(1080, 1350);
            case "1:1", "square" -> new Dimensions(1080, 1080);
            default -> new Dimensions(1080, 1920);
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

    private List<String> safeZonesFor(CreatorShortVideo video) {
        String platform = video == null ? "youtube_shorts" : stringValue(video.getPlatform(), "youtube_shorts").toLowerCase(Locale.ROOT);
        return switch (platform) {
            case "linkedin" -> List.of("caption_safe_bottom", "profile_safe_top");
            case "x" -> List.of("square_center_safe", "caption_safe_bottom");
            default -> List.of("top_caption_safe", "bottom_ui_safe", "right_action_rail_safe");
        };
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

    private boolean containsAny(String value, String... needles) {
        String haystack = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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

    private double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    public record VisualEnhancementResult(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> plans,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }

    private record EnhancementPlan(
            Map<String, Object> plan,
            VisualProfile profile,
            Dimensions target,
            String aspectRatio,
            List<String> riskFlags
    ) {
    }

    private record VisualProfile(
            String name,
            double brightness,
            double contrast,
            double saturation,
            double gamma,
            boolean denoise,
            double denoiseLumaSpatial,
            double denoiseChromaSpatial,
            double denoiseLumaTemporal,
            double denoiseChromaTemporal,
            boolean sharpen,
            double sharpenAmount,
            boolean deband
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("profile", name);
            map.put("brightness", brightness);
            map.put("contrast", contrast);
            map.put("saturation", saturation);
            map.put("gamma", gamma);
            map.put("denoise", denoise);
            map.put("denoiseLumaSpatial", denoiseLumaSpatial);
            map.put("denoiseChromaSpatial", denoiseChromaSpatial);
            map.put("denoiseLumaTemporal", denoiseLumaTemporal);
            map.put("denoiseChromaTemporal", denoiseChromaTemporal);
            map.put("sharpen", sharpen);
            map.put("sharpenAmount", sharpenAmount);
            map.put("deband", deband);
            return map;
        }
    }

    private record Dimensions(int width, int height) {
    }
}
