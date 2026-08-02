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
public class ShortVisualCriticService {

    private static final String SOURCE = "deterministic_visual_critic_worker";
    private static final String RENDERER_CONTRACT = "ShortRenderingService.visualEnhancementPlan.v1";
    private static final Set<String> SUPPORTED_PROFILES = Set.of(
            "balanced_social",
            "talking_head_social",
            "screen_content_clean",
            "low_light_scene_safe",
            "noisy_mobile_clean"
    );

    public VisualCriticResult critique(
            CreatorShortVideo video,
            Map<String, Object> videoDna,
            List<Map<String, Object>> candidatePayloads,
            List<Map<String, Object>> scenes,
            List<Map<String, Object>> frames,
            Map<String, Object> sceneCritic
    ) {
        List<Map<String, Object>> candidates = copyList(candidatePayloads);
        List<Map<String, Object>> safeScenes = copyList(scenes);
        List<Map<String, Object>> safeFrames = copyList(frames);
        if (candidates.isEmpty()) {
            List<Map<String, Object>> trace = List.of(traceRow(
                    "VISUAL_CRITIC",
                    "WARN",
                    "Visual critic could not run because no visual-enhanced candidates were available.",
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
            return new VisualCriticResult(candidates, trace, metadata);
        }

        Map<String, Map<String, Object>> scenesById = indexById(safeScenes);
        Map<String, List<String>> frameIdsByScene = frameIdsByScene(safeFrames);
        String globalSignal = signalText(video, videoDna, safeScenes, sceneCritic);

        List<Map<String, Object>> repairedCandidates = new ArrayList<>();
        List<Map<String, Object>> audits = new ArrayList<>();
        List<Map<String, Object>> repairs = new ArrayList<>();
        int passedCount = 0;
        int warningCount = 0;
        int failedCount = 0;

        for (int index = 0; index < candidates.size(); index++) {
            AuditOutcome outcome = auditCandidate(
                    video,
                    candidates.get(index),
                    safeScenes,
                    scenesById,
                    frameIdsByScene,
                    globalSignal,
                    index + 1
            );
            repairedCandidates.add(outcome.candidate());
            audits.add(outcome.audit());
            repairs.addAll(outcome.repairs());
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
                : (!repairs.isEmpty() || warningCount > 0 ? "COMPLETED_WITH_REPAIRS" : "COMPLETED");
        double confidence = failedCount > 0 ? 0.48 : repairs.isEmpty() && warningCount == 0 ? 0.92 : 0.8;

        Map<String, Object> traceMetadata = new LinkedHashMap<>();
        traceMetadata.put("source", SOURCE);
        traceMetadata.put("candidateCount", repairedCandidates.size());
        traceMetadata.put("passedCount", passedCount);
        traceMetadata.put("warningCount", warningCount);
        traceMetadata.put("failedCount", failedCount);
        traceMetadata.put("repairCount", repairs.size());
        traceMetadata.put("sceneCount", safeScenes.size());
        traceMetadata.put("frameCount", safeFrames.size());
        traceMetadata.put("rendererContract", RENDERER_CONTRACT);

        List<Map<String, Object>> trace = List.of(traceRow(
                "VISUAL_CRITIC",
                status,
                failedCount > 0
                        ? "Visual critic found render-blocking visual plan issues that need review."
                        : repairs.isEmpty()
                        ? "Visual critic verified visual plans, crop policy, enhancement bounds, caption safe zones, and scene/frame coverage."
                        : "Visual critic verified and repaired bounded visual plan issues before rendering.",
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
        metadata.put("repairCount", repairs.size());
        metadata.put("sceneBacked", !safeScenes.isEmpty() && !safeFrames.isEmpty());
        metadata.put("rendererContract", RENDERER_CONTRACT);
        metadata.put("audits", audits);
        metadata.put("repairs", repairs);

        return new VisualCriticResult(repairedCandidates, trace, metadata);
    }

    private AuditOutcome auditCandidate(
            CreatorShortVideo video,
            Map<String, Object> candidatePayload,
            List<Map<String, Object>> scenes,
            Map<String, Map<String, Object>> scenesById,
            Map<String, List<String>> frameIdsByScene,
            String globalSignal,
            int rank
    ) {
        Map<String, Object> candidate = new LinkedHashMap<>(candidatePayload);
        Map<String, Object> renderManifest = mapValue(candidate.get("renderManifest"));
        Map<String, Object> metadata = mapValue(candidate.get("metadata"));
        Map<String, Object> edl = mapValue(candidate.get("editDecisionList"));
        List<Map<String, Object>> segments = listOfMaps(edl.get("segments"));
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> repairs = new ArrayList<>();

        Map<String, Object> plan = mapValue(renderManifest.get("visualEnhancementPlan"));
        if (plan.isEmpty()) {
            plan = fallbackPlan(video, candidate, segments, scenes, frameIdsByScene, rank);
            addIssue(issues, repairs, "high", "VISUAL_PLAN_MISSING", "Missing visual enhancement plan; rebuilt conservative render-safe plan.", true);
        }

        String aspectRatio = stringValue(firstNonEmpty(plan.get("aspectRatio"), renderManifest.get("aspectRatio")), aspectRatioFor(video));
        Dimensions target = targetDimensions(aspectRatio);
        putIfChanged(plan, "aspectRatio", aspectRatio, repairs, "PLAN_ASPECT_RATIO_REPAIRED", "Aligned visual plan aspect ratio with render manifest.");
        putIfChanged(plan, "targetWidth", target.width(), repairs, "PLAN_TARGET_WIDTH_REPAIRED", "Repaired visual plan target width.");
        putIfChanged(plan, "targetHeight", target.height(), repairs, "PLAN_TARGET_HEIGHT_REPAIRED", "Repaired visual plan target height.");
        putIfChanged(plan, "rendererContract", RENDERER_CONTRACT, repairs, "PLAN_RENDERER_CONTRACT_REPAIRED", "Pinned visual plan to supported renderer contract.");

        if (segments.isEmpty()) {
            addIssue(issues, repairs, "high", "NO_EDL_SEGMENTS", "Visual plan cannot be verified because candidate has no EDL segments.", false);
        }

        List<String> riskFlags = mergeRiskFlags(plan, globalSignal, segments, scenesById, frameIdsByScene);
        boolean preserveFullFrame = riskFlags.contains("PRESERVE_FULL_FRAME");
        String profile = supportedProfile(stringValue(firstNonEmpty(plan.get("filterProfile"), mapValue(plan.get("enhancements")).get("profile")), "balanced_social"), riskFlags, preserveFullFrame);
        String expectedLayout = preserveFullFrame || "screen_content_clean".equals(profile) ? "fit_with_pad" : "fill_crop";

        putIfChanged(plan, "filterProfile", profile, repairs, "PLAN_PROFILE_REPAIRED", "Aligned filter profile with visual risk flags.");
        putIfChanged(plan, "layoutMode", expectedLayout, repairs, "PLAN_LAYOUT_REPAIRED", "Aligned layout mode with crop risk and content type.");
        putIfChanged(plan, "scaleMode", "fit_with_pad".equals(expectedLayout) ? "fit" : "fill", repairs, "PLAN_SCALE_MODE_REPAIRED", "Aligned scale mode with layout.");
        putIfChanged(plan, "cropMode", "fit_with_pad".equals(expectedLayout) ? "preserve_full_frame" : "center_safe_crop", repairs, "PLAN_CROP_MODE_REPAIRED", "Aligned crop mode with layout.");

        Map<String, Object> enhancements = normalizeEnhancements(mapValue(plan.get("enhancements")), profile, riskFlags, repairs);
        plan.put("enhancements", enhancements);

        Map<String, Object> captionStyle = normalizeCaptionStyle(video, mapValue(plan.get("captionStyle")), target, profile, repairs);
        plan.put("captionStyle", captionStyle);

        List<String> safeZones = listOfStrings(plan.get("safeZones"));
        if (safeZones.isEmpty()) {
            safeZones = safeZonesFor(video);
            plan.put("safeZones", safeZones);
            addRepair(repairs, "SAFE_ZONES_REPAIRED", "Restored platform safe zones to visual plan.");
        }

        List<Map<String, Object>> segmentPlans = normalizeSegmentPlans(
                segments,
                listOfMaps(plan.get("segmentPlans")),
                scenes,
                scenesById,
                frameIdsByScene,
                expectedLayout,
                profile,
                riskFlags,
                repairs,
                issues
        );
        plan.put("segmentPlans", segmentPlans);
        plan.put("riskFlags", riskFlags);
        plan.put("sceneCoverage", sceneCoverage(segments, segmentPlans, scenes));
        plan.put("renderInstructions", renderInstructions());
        plan.put("visualCriticCheckedAt", OffsetDateTime.now().toString());
        plan.put("visualCriticStatus", repairs.isEmpty() && noUnresolvedHigh(issues) ? "PASS" : (noUnresolvedHigh(issues) ? "WARN" : "FAIL"));

        if (segments.size() != segmentPlans.size()) {
            addIssue(issues, repairs, "high", "SEGMENT_PLAN_COUNT_MISMATCH", "Visual segment plan count does not match EDL segment count.", false);
        }
        if (!scenes.isEmpty() && segmentPlans.stream().noneMatch(segment -> !stringValue(segment.get("sceneId"), "").isBlank())) {
            addIssue(issues, repairs, "medium", "NO_SCENE_COVERAGE", "Visual plan has no scene ids even though scene analysis exists.", false);
        }
        if (!frameIdsByScene.isEmpty() && segmentPlans.stream().noneMatch(segment -> !listOfStrings(segment.get("frameIds")).isEmpty())) {
            addIssue(issues, repairs, "medium", "NO_FRAME_COVERAGE", "Visual plan has no frame ids even though representative frames exist.", false);
        }

        String status = noUnresolvedHigh(issues)
                ? (issues.isEmpty() && repairs.isEmpty() ? "PASS" : "WARN")
                : "FAIL";
        double confidence = "PASS".equals(status) ? 0.92 : ("WARN".equals(status) ? 0.78 : 0.42);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("source", SOURCE);
        audit.put("status", status);
        audit.put("passed", !"FAIL".equals(status));
        audit.put("confidence", confidence);
        audit.put("candidateRank", rank);
        audit.put("candidateTitle", stringValue(candidate.get("title"), ""));
        audit.put("profile", profile);
        audit.put("layoutMode", expectedLayout);
        audit.put("riskFlags", riskFlags);
        audit.put("issueCount", issues.size());
        audit.put("repairCount", repairs.size());
        audit.put("issues", issues);
        audit.put("repairs", repairs);
        audit.put("rendererContract", RENDERER_CONTRACT);
        audit.put("timestamp", OffsetDateTime.now().toString());

        renderManifest.put("visualEnhancementPlan", plan);
        renderManifest.put("visualCritic", audit);
        renderManifest.put("visualCriticStatus", status);
        renderManifest.put("visualEnhancementStatus", repairs.isEmpty() ? "READY_FOR_RENDER" : "CRITIC_REPAIRED");
        renderManifest.put("targetWidth", target.width());
        renderManifest.put("targetHeight", target.height());
        renderManifest.put("aspectRatio", aspectRatio);

        metadata.put("visualCritic", audit);
        metadata.put("visualCriticStatus", status);
        metadata.put("visualCriticSource", SOURCE);
        metadata.put("visualCriticRepairCount", repairs.size());
        metadata.put("visualCriticIssueCount", issues.size());
        metadata.put("visualEnhancementPlan", plan);
        metadata.put("visualEnhancementProfile", profile);
        metadata.put("visualEnhancementRiskFlags", riskFlags);

        candidate.put("renderManifest", renderManifest);
        candidate.put("metadata", metadata);
        return new AuditOutcome(candidate, audit, repairs);
    }

    private Map<String, Object> normalizeEnhancements(
            Map<String, Object> enhancements,
            String profile,
            List<String> riskFlags,
            List<Map<String, Object>> repairs
    ) {
        if (enhancements.isEmpty()) {
            enhancements = defaultEnhancements(profile);
            addRepair(repairs, "ENHANCEMENTS_REBUILT", "Rebuilt missing visual enhancement filter settings.");
        }

        double brightness = clamp(doubleValue(enhancements.get("brightness"), defaultDouble(profile, "brightness")), -0.12, 0.12);
        double contrast = clamp(doubleValue(enhancements.get("contrast"), defaultDouble(profile, "contrast")), 0.8, 1.35);
        double saturation = clamp(doubleValue(enhancements.get("saturation"), defaultDouble(profile, "saturation")), 0.7, 1.45);
        double gamma = clamp(doubleValue(enhancements.get("gamma"), defaultDouble(profile, "gamma")), 0.75, 1.35);
        boolean denoise = booleanValue(enhancements.get("denoise"), defaultBoolean(profile, "denoise"));
        boolean sharpen = booleanValue(enhancements.get("sharpen"), defaultBoolean(profile, "sharpen"));

        if (riskFlags.contains("LOW_LIGHT")) {
            brightness = Math.max(brightness, 0.015);
            contrast = Math.max(contrast, 1.05);
            denoise = true;
        }
        if (riskFlags.contains("OVEREXPOSED")) {
            brightness = Math.min(brightness, 0.0);
            contrast = Math.min(contrast, 1.08);
        }
        if (riskFlags.contains("NOISY_SOURCE") || riskFlags.contains("MOTION_BLUR")) {
            denoise = true;
        }
        if ("screen_content_clean".equals(profile)) {
            saturation = clamp(saturation, 0.95, 1.08);
            brightness = clamp(brightness, -0.02, 0.02);
            denoise = false;
            sharpen = true;
        }

        putIfChanged(enhancements, "profile", profile, repairs, "ENHANCEMENT_PROFILE_REPAIRED", "Aligned enhancement profile field.");
        putIfChanged(enhancements, "brightness", round3(brightness), repairs, "BRIGHTNESS_REPAIRED", "Clamped brightness to renderer-safe range.");
        putIfChanged(enhancements, "contrast", round3(contrast), repairs, "CONTRAST_REPAIRED", "Clamped contrast to renderer-safe range.");
        putIfChanged(enhancements, "saturation", round3(saturation), repairs, "SATURATION_REPAIRED", "Clamped saturation to renderer-safe range.");
        putIfChanged(enhancements, "gamma", round3(gamma), repairs, "GAMMA_REPAIRED", "Clamped gamma to renderer-safe range.");
        putIfChanged(enhancements, "denoise", denoise, repairs, "DENOISE_REPAIRED", "Aligned denoise flag with visual risk.");
        putIfChanged(enhancements, "sharpen", sharpen, repairs, "SHARPEN_REPAIRED", "Aligned sharpen flag with visual profile.");
        putIfChanged(enhancements, "denoiseLumaSpatial", round3(clamp(doubleValue(enhancements.get("denoiseLumaSpatial"), defaultDouble(profile, "denoiseLumaSpatial")), 0.0, 3.0)), repairs, "DENOISE_LUMA_SPATIAL_REPAIRED", "Clamped luma spatial denoise.");
        putIfChanged(enhancements, "denoiseChromaSpatial", round3(clamp(doubleValue(enhancements.get("denoiseChromaSpatial"), defaultDouble(profile, "denoiseChromaSpatial")), 0.0, 3.0)), repairs, "DENOISE_CHROMA_SPATIAL_REPAIRED", "Clamped chroma spatial denoise.");
        putIfChanged(enhancements, "denoiseLumaTemporal", round3(clamp(doubleValue(enhancements.get("denoiseLumaTemporal"), defaultDouble(profile, "denoiseLumaTemporal")), 0.0, 8.0)), repairs, "DENOISE_LUMA_TEMPORAL_REPAIRED", "Clamped luma temporal denoise.");
        putIfChanged(enhancements, "denoiseChromaTemporal", round3(clamp(doubleValue(enhancements.get("denoiseChromaTemporal"), defaultDouble(profile, "denoiseChromaTemporal")), 0.0, 8.0)), repairs, "DENOISE_CHROMA_TEMPORAL_REPAIRED", "Clamped chroma temporal denoise.");
        putIfChanged(enhancements, "sharpenAmount", round3(clamp(doubleValue(enhancements.get("sharpenAmount"), defaultDouble(profile, "sharpenAmount")), 0.0, 0.9)), repairs, "SHARPEN_AMOUNT_REPAIRED", "Clamped sharpen amount.");
        putIfChanged(enhancements, "deband", booleanValue(enhancements.get("deband"), false), repairs, "DEBAND_REPAIRED", "Normalized deband flag.");
        return enhancements;
    }

    private List<Map<String, Object>> normalizeSegmentPlans(
            List<Map<String, Object>> segments,
            List<Map<String, Object>> existingPlans,
            List<Map<String, Object>> scenes,
            Map<String, Map<String, Object>> scenesById,
            Map<String, List<String>> frameIdsByScene,
            String layoutMode,
            String profile,
            List<String> globalRiskFlags,
            List<Map<String, Object>> repairs,
            List<Map<String, Object>> issues
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            Map<String, Object> segment = segments.get(index);
            Map<String, Object> plan = segmentPlanFor(existingPlans, index, stringValue(segment.get("nodeId"), ""));
            if (plan.isEmpty()) {
                plan = new LinkedHashMap<>();
                addIssue(issues, repairs, "medium", "SEGMENT_VISUAL_PLAN_MISSING", "Rebuilt missing visual segment plan for index " + index + ".", true);
            }
            double sourceStart = Math.max(0.0, doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0)));
            double sourceEnd = Math.max(sourceStart + 0.1, doubleValue(segment.get("sourceEnd"), doubleValue(segment.get("end"), sourceStart + 0.1)));
            String sceneId = sceneIdFor(segment, plan, scenes, scenesById);
            List<String> frameIds = listOfStrings(plan.get("frameIds"));
            if (frameIds.isEmpty() && !sceneId.isBlank()) {
                frameIds = new ArrayList<>(frameIdsByScene.getOrDefault(sceneId, List.of()));
                if (!frameIds.isEmpty()) {
                    addRepair(repairs, "SEGMENT_FRAME_IDS_REPAIRED", "Attached representative frame ids to visual segment plan.");
                }
            }

            List<String> segmentRiskFlags = new ArrayList<>(new LinkedHashSet<>(globalRiskFlags));
            if (sceneId.isBlank() && !scenes.isEmpty()) {
                segmentRiskFlags.add("SCENE_REFERENCE_MISSING");
            }
            if (frameIds.isEmpty() && !frameIdsByScene.isEmpty()) {
                segmentRiskFlags.add("MISSING_FRAME_EVIDENCE");
            }

            putIfChanged(plan, "index", index, repairs, "SEGMENT_INDEX_REPAIRED", "Repaired visual segment index.");
            putIfChanged(plan, "nodeId", stringValue(segment.get("nodeId"), ""), repairs, "SEGMENT_NODE_ID_REPAIRED", "Aligned visual segment node id.");
            putIfChanged(plan, "sceneId", sceneId, repairs, "SEGMENT_SCENE_ID_REPAIRED", "Aligned visual segment scene id.");
            putIfChanged(plan, "sourceStart", round3(sourceStart), repairs, "SEGMENT_SOURCE_START_REPAIRED", "Aligned visual segment source start.");
            putIfChanged(plan, "sourceEnd", round3(sourceEnd), repairs, "SEGMENT_SOURCE_END_REPAIRED", "Aligned visual segment source end.");
            putIfChanged(plan, "timelineStart", round3(doubleValue(segment.get("timelineStart"), 0.0)), repairs, "SEGMENT_TIMELINE_START_REPAIRED", "Aligned visual segment timeline start.");
            putIfChanged(plan, "timelineEnd", round3(doubleValue(segment.get("timelineEnd"), 0.0)), repairs, "SEGMENT_TIMELINE_END_REPAIRED", "Aligned visual segment timeline end.");
            putIfChanged(plan, "filterProfile", profile, repairs, "SEGMENT_PROFILE_REPAIRED", "Aligned visual segment filter profile.");
            putIfChanged(plan, "layoutMode", layoutMode, repairs, "SEGMENT_LAYOUT_REPAIRED", "Aligned visual segment layout.");
            putIfChanged(plan, "cropMode", "fit_with_pad".equals(layoutMode) ? "preserve_full_frame" : "center_safe_crop", repairs, "SEGMENT_CROP_REPAIRED", "Aligned visual segment crop mode.");
            putIfChanged(plan, "cropAnchor", "center", repairs, "SEGMENT_CROP_ANCHOR_REPAIRED", "Aligned visual segment crop anchor.");
            putIfChanged(plan, "riskFlags", segmentRiskFlags, repairs, "SEGMENT_RISK_FLAGS_REPAIRED", "Updated visual segment risk flags.");
            putIfChanged(plan, "frameIds", frameIds, repairs, "SEGMENT_FRAME_IDS_REPAIRED", "Aligned visual segment frame ids.");
            result.add(plan);
        }
        return result;
    }

    private Map<String, Object> normalizeCaptionStyle(
            CreatorShortVideo video,
            Map<String, Object> style,
            Dimensions target,
            String profile,
            List<Map<String, Object>> repairs
    ) {
        if (style.isEmpty()) {
            style = new LinkedHashMap<>();
            addRepair(repairs, "CAPTION_STYLE_REBUILT", "Rebuilt missing visual caption style.");
        }
        int defaultFontSize = Math.max(42, Math.min("screen_content_clean".equals(profile) ? 64 : 78, target.height() / 26));
        int fontSize = clampInt(intValue(style.get("fontSize"), defaultFontSize), 34, "screen_content_clean".equals(profile) ? 64 : 82);
        int bottomMargin = clampInt(intValue(style.get("bottomMargin"), Math.max(120, target.height() / 9)), Math.max(80, target.height() / 14), Math.max(160, target.height() / 4));
        int sideMargin = Math.max(60, target.width() / 16);
        int leftMargin = clampInt(intValue(style.get("leftMargin"), sideMargin), 40, Math.max(80, target.width() / 4));
        int rightMargin = clampInt(intValue(style.get("rightMargin"), sideMargin), 40, Math.max(80, target.width() / 4));
        int maxChars = clampInt(intValue(style.get("maxCharsPerLine"), "linkedin".equalsIgnoreCase(video == null ? "" : stringValue(video.getPlatform(), "")) ? 32 : 28), 20, 38);

        putIfChanged(style, "source", SOURCE, repairs, "CAPTION_SOURCE_REPAIRED", "Pinned caption style source to visual critic.");
        putIfChanged(style, "fontName", sanitizeFont(stringValue(style.get("fontName"), "Noto Sans")), repairs, "CAPTION_FONT_REPAIRED", "Sanitized caption font name.");
        putIfChanged(style, "fontSize", fontSize, repairs, "CAPTION_FONT_SIZE_REPAIRED", "Clamped caption font size to target resolution.");
        putIfChanged(style, "bold", booleanValue(style.get("bold"), true), repairs, "CAPTION_BOLD_REPAIRED", "Normalized caption bold flag.");
        putIfChanged(style, "outline", round3(clamp(doubleValue(style.get("outline"), "screen_content_clean".equals(profile) ? 3.5 : 4.0), 2.0, 6.0)), repairs, "CAPTION_OUTLINE_REPAIRED", "Clamped caption outline.");
        putIfChanged(style, "shadow", round3(clamp(doubleValue(style.get("shadow"), 2.0), 0.0, 4.0)), repairs, "CAPTION_SHADOW_REPAIRED", "Clamped caption shadow.");
        putIfChanged(style, "alignment", 2, repairs, "CAPTION_ALIGNMENT_REPAIRED", "Pinned captions to lower-center safe alignment.");
        putIfChanged(style, "leftMargin", leftMargin, repairs, "CAPTION_LEFT_MARGIN_REPAIRED", "Clamped caption left margin.");
        putIfChanged(style, "rightMargin", rightMargin, repairs, "CAPTION_RIGHT_MARGIN_REPAIRED", "Clamped caption right margin.");
        putIfChanged(style, "bottomMargin", bottomMargin, repairs, "CAPTION_BOTTOM_MARGIN_REPAIRED", "Clamped caption bottom safe-zone margin.");
        putIfChanged(style, "maxCharsPerLine", maxChars, repairs, "CAPTION_LINE_LENGTH_REPAIRED", "Clamped caption line length.");
        putIfChanged(style, "safeZone", "bottom_ui_safe", repairs, "CAPTION_SAFE_ZONE_REPAIRED", "Pinned caption style to bottom safe zone.");
        return style;
    }

    private Map<String, Object> fallbackPlan(
            CreatorShortVideo video,
            Map<String, Object> candidate,
            List<Map<String, Object>> segments,
            List<Map<String, Object>> scenes,
            Map<String, List<String>> frameIdsByScene,
            int rank
    ) {
        String aspectRatio = aspectRatioFor(video);
        Dimensions target = targetDimensions(aspectRatio);
        List<Map<String, Object>> segmentPlans = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            Map<String, Object> segment = segments.get(index);
            String sceneId = sceneIdAt(scenes, doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0)));
            Map<String, Object> segmentPlan = new LinkedHashMap<>();
            segmentPlan.put("index", index);
            segmentPlan.put("nodeId", stringValue(segment.get("nodeId"), ""));
            segmentPlan.put("sceneId", sceneId);
            segmentPlan.put("sourceStart", round3(doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0))));
            segmentPlan.put("sourceEnd", round3(doubleValue(segment.get("sourceEnd"), doubleValue(segment.get("end"), 0.0))));
            segmentPlan.put("timelineStart", round3(doubleValue(segment.get("timelineStart"), 0.0)));
            segmentPlan.put("timelineEnd", round3(doubleValue(segment.get("timelineEnd"), 0.0)));
            segmentPlan.put("filterProfile", "balanced_social");
            segmentPlan.put("layoutMode", "fill_crop");
            segmentPlan.put("cropMode", "center_safe_crop");
            segmentPlan.put("cropAnchor", "center");
            segmentPlan.put("riskFlags", List.of("VISUAL_PLAN_REBUILT_BY_CRITIC"));
            segmentPlan.put("frameIds", frameIdsByScene.getOrDefault(sceneId, List.of()));
            segmentPlans.add(segmentPlan);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("source", SOURCE);
        plan.put("version", 1);
        plan.put("generatedAt", OffsetDateTime.now().toString());
        plan.put("status", "REBUILT_BY_VISUAL_CRITIC");
        plan.put("rankIndex", rank);
        plan.put("candidateTitle", stringValue(candidate.get("title"), ""));
        plan.put("aspectRatio", aspectRatio);
        plan.put("targetWidth", target.width());
        plan.put("targetHeight", target.height());
        plan.put("layoutMode", "fill_crop");
        plan.put("scaleMode", "fill");
        plan.put("cropMode", "center_safe_crop");
        plan.put("filterProfile", "balanced_social");
        plan.put("enhancements", defaultEnhancements("balanced_social"));
        plan.put("captionStyle", defaultCaptionStyle(video, target, "balanced_social"));
        plan.put("safeZones", safeZonesFor(video));
        plan.put("segmentPlans", segmentPlans);
        plan.put("riskFlags", List.of("VISUAL_PLAN_REBUILT_BY_CRITIC"));
        plan.put("rendererContract", RENDERER_CONTRACT);
        plan.put("renderInstructions", renderInstructions());
        return plan;
    }

    private String supportedProfile(String profile, List<String> riskFlags, boolean preserveFullFrame) {
        String normalized = stringValue(profile, "balanced_social").toLowerCase(Locale.ROOT);
        if (preserveFullFrame) {
            return "screen_content_clean";
        }
        if (riskFlags.contains("LOW_LIGHT")) {
            return "low_light_scene_safe";
        }
        if (riskFlags.contains("NOISY_SOURCE") || riskFlags.contains("MOTION_BLUR")) {
            return "noisy_mobile_clean";
        }
        return SUPPORTED_PROFILES.contains(normalized) ? normalized : "balanced_social";
    }

    private List<String> mergeRiskFlags(
            Map<String, Object> plan,
            String globalSignal,
            List<Map<String, Object>> segments,
            Map<String, Map<String, Object>> scenesById,
            Map<String, List<String>> frameIdsByScene
    ) {
        Set<String> flags = new LinkedHashSet<>(listOfStrings(plan.get("riskFlags")));
        if (containsAny(globalSignal, "screen", "presentation", "slide", "dashboard", "code", "whiteboard")) flags.add("PRESERVE_FULL_FRAME");
        if (containsAny(globalSignal, "low light", "underexposed", "too dark", "night")) flags.add("LOW_LIGHT");
        if (containsAny(globalSignal, "overexposed", "washed out", "glare", "bright highlight")) flags.add("OVEREXPOSED");
        if (containsAny(globalSignal, "noise", "grain", "artifact", "pixelated", "compression")) flags.add("NOISY_SOURCE");
        if (containsAny(globalSignal, "motion blur", "shaky", "camera shake", "fast movement")) flags.add("MOTION_BLUR");
        if (segments == null || segments.isEmpty()) flags.add("NO_SEGMENT_EVIDENCE");
        for (Map<String, Object> segment : segments == null ? List.<Map<String, Object>>of() : segments) {
            String sceneId = stringValue(segment.get("sceneId"), "");
            if (!sceneId.isBlank() && !scenesById.containsKey(sceneId)) {
                flags.add("SCENE_REFERENCE_MISSING");
            }
            if (!sceneId.isBlank() && frameIdsByScene.containsKey(sceneId) && frameIdsByScene.get(sceneId).isEmpty()) {
                flags.add("MISSING_FRAME_EVIDENCE");
            }
        }
        flags.remove("");
        return new ArrayList<>(flags);
    }

    private Map<String, Object> sceneCoverage(List<Map<String, Object>> segments, List<Map<String, Object>> segmentPlans, List<Map<String, Object>> scenes) {
        Set<String> sceneIds = new LinkedHashSet<>();
        int frameCovered = 0;
        for (Map<String, Object> segmentPlan : segmentPlans) {
            String sceneId = stringValue(segmentPlan.get("sceneId"), "");
            if (!sceneId.isBlank()) {
                sceneIds.add(sceneId);
            }
            if (!listOfStrings(segmentPlan.get("frameIds")).isEmpty()) {
                frameCovered++;
            }
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("segmentCount", segments == null ? 0 : segments.size());
        coverage.put("visualSegmentPlanCount", segmentPlans.size());
        coverage.put("coveredSceneCount", sceneIds.size());
        coverage.put("availableSceneCount", scenes == null ? 0 : scenes.size());
        coverage.put("frameCoveredSegmentCount", frameCovered);
        coverage.put("coveredSceneIds", new ArrayList<>(sceneIds));
        coverage.put("coverageRatio", scenes == null || scenes.isEmpty() ? 0.0 : round3(sceneIds.size() / (double) scenes.size()));
        return coverage;
    }

    private Map<String, Object> defaultEnhancements(String profile) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("profile", profile);
        map.put("brightness", defaultDouble(profile, "brightness"));
        map.put("contrast", defaultDouble(profile, "contrast"));
        map.put("saturation", defaultDouble(profile, "saturation"));
        map.put("gamma", defaultDouble(profile, "gamma"));
        map.put("denoise", defaultBoolean(profile, "denoise"));
        map.put("denoiseLumaSpatial", defaultDouble(profile, "denoiseLumaSpatial"));
        map.put("denoiseChromaSpatial", defaultDouble(profile, "denoiseChromaSpatial"));
        map.put("denoiseLumaTemporal", defaultDouble(profile, "denoiseLumaTemporal"));
        map.put("denoiseChromaTemporal", defaultDouble(profile, "denoiseChromaTemporal"));
        map.put("sharpen", defaultBoolean(profile, "sharpen"));
        map.put("sharpenAmount", defaultDouble(profile, "sharpenAmount"));
        map.put("deband", false);
        return map;
    }

    private Map<String, Object> defaultCaptionStyle(CreatorShortVideo video, Dimensions target, String profile) {
        Map<String, Object> style = new LinkedHashMap<>();
        style.put("source", SOURCE);
        style.put("fontName", "Noto Sans");
        style.put("fontSize", Math.max(42, Math.min("screen_content_clean".equals(profile) ? 64 : 78, target.height() / 26)));
        style.put("bold", true);
        style.put("outline", "screen_content_clean".equals(profile) ? 3.5 : 4.0);
        style.put("shadow", 2.0);
        style.put("alignment", 2);
        style.put("leftMargin", Math.max(70, target.width() / 14));
        style.put("rightMargin", Math.max(70, target.width() / 14));
        style.put("bottomMargin", Math.max(120, target.height() / 9));
        style.put("maxCharsPerLine", "linkedin".equalsIgnoreCase(video == null ? "" : stringValue(video.getPlatform(), "")) ? 32 : 28);
        style.put("safeZone", "bottom_ui_safe");
        return style;
    }

    private double defaultDouble(String profile, String key) {
        String normalized = stringValue(profile, "balanced_social");
        if ("screen_content_clean".equals(normalized)) {
            return switch (key) {
                case "contrast" -> 1.02;
                case "saturation", "gamma" -> 1.0;
                case "sharpenAmount" -> 0.62;
                default -> 0.0;
            };
        }
        if ("low_light_scene_safe".equals(normalized)) {
            return switch (key) {
                case "brightness" -> 0.025;
                case "contrast" -> 1.08;
                case "saturation" -> 1.06;
                case "gamma" -> 1.04;
                case "denoiseLumaSpatial" -> 1.6;
                case "denoiseChromaSpatial" -> 1.2;
                case "denoiseLumaTemporal" -> 4.8;
                case "denoiseChromaTemporal" -> 3.6;
                case "sharpenAmount" -> 0.35;
                default -> 0.0;
            };
        }
        if ("noisy_mobile_clean".equals(normalized)) {
            return switch (key) {
                case "brightness" -> 0.008;
                case "contrast", "saturation", "gamma" -> "gamma".equals(key) ? 1.0 : 1.05;
                case "denoiseLumaSpatial" -> 1.4;
                case "denoiseChromaSpatial" -> 1.1;
                case "denoiseLumaTemporal" -> 4.2;
                case "denoiseChromaTemporal" -> 3.2;
                case "sharpenAmount" -> 0.3;
                default -> 0.0;
            };
        }
        if ("talking_head_social".equals(normalized)) {
            return switch (key) {
                case "brightness" -> 0.004;
                case "contrast" -> 1.04;
                case "saturation" -> 1.07;
                case "gamma" -> 1.0;
                case "denoiseLumaSpatial" -> 1.0;
                case "denoiseChromaSpatial" -> 0.8;
                case "denoiseLumaTemporal" -> 3.2;
                case "denoiseChromaTemporal" -> 2.4;
                case "sharpenAmount" -> 0.42;
                default -> 0.0;
            };
        }
        return switch (key) {
            case "contrast" -> 1.04;
            case "saturation" -> 1.08;
            case "gamma" -> 1.0;
            case "denoiseLumaSpatial" -> 0.8;
            case "denoiseChromaSpatial" -> 0.6;
            case "denoiseLumaTemporal" -> 2.8;
            case "denoiseChromaTemporal" -> 2.1;
            case "sharpenAmount" -> 0.45;
            default -> 0.0;
        };
    }

    private boolean defaultBoolean(String profile, String key) {
        if ("deband".equals(key)) {
            return false;
        }
        if ("denoise".equals(key)) {
            return !"screen_content_clean".equals(profile);
        }
        if ("sharpen".equals(key)) {
            return true;
        }
        return false;
    }

    private List<String> renderInstructions() {
        return List.of(
                "normalize_every_edl_segment_to_target_dimensions",
                "apply_layout_scale_crop_or_pad",
                "apply_denoise_eq_and_sharpen_filters",
                "burn_ass_captions_inside_platform_safe_zone",
                "upload_rendered_mp4_to_minio"
        );
    }

    private Map<String, Object> segmentPlanFor(List<Map<String, Object>> plans, int index, String nodeId) {
        for (Map<String, Object> plan : plans == null ? List.<Map<String, Object>>of() : plans) {
            if (intValue(plan.get("index"), -1) == index) {
                return new LinkedHashMap<>(plan);
            }
            String planNodeId = stringValue(plan.get("nodeId"), "");
            if (!nodeId.isBlank() && nodeId.equals(planNodeId)) {
                return new LinkedHashMap<>(plan);
            }
        }
        return new LinkedHashMap<>();
    }

    private String sceneIdFor(
            Map<String, Object> segment,
            Map<String, Object> segmentPlan,
            List<Map<String, Object>> scenes,
            Map<String, Map<String, Object>> scenesById
    ) {
        String fromPlan = stringValue(segmentPlan.get("sceneId"), "");
        if (!fromPlan.isBlank() && scenesById.containsKey(fromPlan)) {
            return fromPlan;
        }
        String fromSegment = stringValue(segment.get("sceneId"), "");
        if (!fromSegment.isBlank() && scenesById.containsKey(fromSegment)) {
            return fromSegment;
        }
        double start = doubleValue(segment.get("sourceStart"), doubleValue(segment.get("start"), 0.0));
        double end = doubleValue(segment.get("sourceEnd"), doubleValue(segment.get("end"), start));
        return sceneIdAt(scenes, start + Math.max(0.0, end - start) / 2.0);
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

    private Dimensions targetDimensions(String aspectRatio) {
        String normalized = stringValue(aspectRatio, "9:16").trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "16:9", "horizontal", "landscape" -> new Dimensions(1920, 1080);
            case "4:5" -> new Dimensions(1080, 1350);
            case "1:1", "square" -> new Dimensions(1080, 1080);
            default -> new Dimensions(1080, 1920);
        };
    }

    private String signalText(CreatorShortVideo video, Map<String, Object> videoDna, List<Map<String, Object>> scenes, Map<String, Object> sceneCritic) {
        StringBuilder builder = new StringBuilder();
        builder.append(video == null ? "" : stringValue(video.getPlatform(), "")).append(' ');
        builder.append(video == null ? "" : stringValue(video.getTitle(), "")).append(' ');
        builder.append(videoDna == null ? "" : videoDna).append(' ');
        builder.append(sceneCritic == null ? "" : sceneCritic).append(' ');
        for (Map<String, Object> scene : scenes == null ? List.<Map<String, Object>>of() : scenes) {
            builder.append(scene).append(' ');
        }
        return builder.toString().toLowerCase(Locale.ROOT);
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

    private Map<String, List<String>> frameIdsByScene(List<Map<String, Object>> frames) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map<String, Object> frame : frames == null ? List.<Map<String, Object>>of() : frames) {
            String sceneId = stringValue(frame.get("sceneId"), "");
            String frameId = stringValue(frame.get("id"), "");
            if (!sceneId.isBlank() && !frameId.isBlank()) {
                result.computeIfAbsent(sceneId, ignored -> new ArrayList<>()).add(frameId);
            }
        }
        return result;
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

    private String sanitizeFont(String value) {
        String safe = stringValue(value, "Noto Sans").replace(",", " ").replace("\r", " ").replace("\n", " ").trim();
        String normalized = safe.toLowerCase(Locale.ROOT);
        if (safe.isBlank()
                || "arial".equals(normalized)
                || "helvetica".equals(normalized)
                || "sans-serif".equals(normalized)
                || "system".equals(normalized)
                || "default".equals(normalized)
                || normalized.contains("emoji")) {
            return "Noto Sans";
        }
        if (normalized.startsWith("noto ")
                || normalized.startsWith("dejavu ")
                || normalized.startsWith("liberation ")) {
            return safe;
        }
        return "Noto Sans";
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

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            if (List.of("true", "yes", "1", "on").contains(normalized)) return true;
            if (List.of("false", "no", "0", "off").contains(normalized)) return false;
        }
        return fallback;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    private record Dimensions(int width, int height) {
    }

    public record VisualCriticResult(
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> trace,
            Map<String, Object> metadata
    ) {
    }
}
