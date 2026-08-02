package com.dalai.llama.creator.ai;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MockCreatorAiProvider implements CreatorAiProvider {

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public Map<String, Object> generate(String promptType, Map<String, Object> input) {
        if ("TREND_PREDICT".equals(promptType)) {
            return generateTrendPredictions(promptType, input);
        }
        if ("TREND_INSIGHT".equals(promptType)) {
            return generateTrendInsight(promptType, input);
        }
        if ("IDEA_GENERATE".equals(promptType)) {
            return generateIdeaCandidates(promptType, input);
        }
        if ("CAMPAIGN_ANGLE_SUGGEST".equals(promptType)) {
            return generateCampaignAngles(promptType, input);
        }
        if ("WEEKLY_IDEA_TAGS".equals(promptType)) {
            return generateWeeklyIdeaTags(promptType, input);
        }
        if ("SHOT_JSON_EDIT".equals(promptType)) {
            return generateShotJsonEdit(promptType, input);
        }
        if ("CLIENT_FEEDBACK_PROPAGATE".equals(promptType)) {
            return generateClientFeedbackPropagation(promptType, input);
        }
        if ("CLIENT_REVIEW_RAG_CHAT".equals(promptType)) {
            return generateClientReviewRagChat(promptType, input);
        }
        if ("SCREENPLAY_DIALOGUE_LOCALIZE".equals(promptType)) {
            return generateLocalizedDialogue(promptType, input);
        }
        if ("SHORTS_GENERATE".equals(promptType)) {
            return generateShorts(promptType, input);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("inputHash", Integer.toHexString(input.hashCode()));
        output.put("status", "deterministic_mock_ready");
        return output;
    }

    private Map<String, Object> generateClientReviewRagChat(String promptType, Map<String, Object> input) {
        Map<String, Object> ragContext = mapValue(input.get("ragContext"));
        Map<String, Object> currentShot = mapValue(ragContext.get("selectedShot"));
        String message = defaultString(input.get("message"), "Apply the requested client revision.");
        int shotNumber = intValue(input.get("shotNumber"), intValue(currentShot.get("shotNumber"), 0));
        Map<String, Object> proposedShot = new LinkedHashMap<>(currentShot);
        if (!proposedShot.isEmpty()) {
            proposedShot.put("revisionRequest", message);
            proposedShot.put("revisionReason", "Client review RAG chat");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("status", "deterministic_mock_ready");
        output.put(
                "assistantMessage",
                "I retrieved Shot " + shotNumber + ", its adjacent shots, production plan, current images, and review history. I will apply: " + message
        );
        output.put("changeSummary", message);
        output.put("imageRevisionPrompt", message + " Preserve product identity, composition continuity, and approved references.");
        output.put("proposedShot", proposedShot);
        output.put("proposedOverlayPlan", mapValue(ragContext.get("selectedOverlayPlan")));
        output.put("requiresFrameRegeneration", !"PLANNING".equals(String.valueOf(input.get("targetType"))));
        output.put("affectedPlanningStages", List.of("screenplay", "shot_plan", "storyboard", "production_frames", "video_handoff"));
        output.put("attachedReferenceImageCount", Math.min(8, Math.max(
                stringList(input.get("referenceImageUrls")).size(),
                mapList(input.get("referenceImageAssets")).size()
        )));
        return output;
    }

    private Map<String, Object> generateClientFeedbackPropagation(String promptType, Map<String, Object> input) {
        List<Map<String, Object>> currentShots = mapList(input.get("currentShots"));
        String dialogueLanguage = defaultString(
                mapValue(input.get("constraints")).get("dialogueLanguage"), "English");
        Map<String, Object> typography = new LinkedHashMap<>();
        typography.put("primaryFont", "Montserrat");
        typography.put("primaryWeight", 800);
        typography.put("secondaryFont", "Inter");
        typography.put("secondaryWeight", 600);
        typography.put("fallbackStack", "Arial, sans-serif");
        typography.put("caseRule", "Sentence case; uppercase only for short hooks");
        typography.put("maxLines", 2);
        typography.put("decisionSource", "Mock AI creative-direction decision");
        if (!mapList(input.get("fontReferenceImages")).isEmpty()) {
            typography.put("matchedFromFontReferences", true);
            typography.put("approximation", true);
            typography.put("confidence", "medium");
            typography.put("styleTraits", List.of("geometric", "high-impact", "clean"));
            typography.put("matchRationale", "Montserrat is the closest renderable match to the uploaded lettering sample.");
        }

        List<Map<String, Object>> overlays = new ArrayList<>();
        List<Map<String, Object>> revisedShots = new ArrayList<>();
        for (int index = 0; index < currentShots.size(); index++) {
            Map<String, Object> shot = new LinkedHashMap<>(currentShots.get(index));
            int shotNumber = intValue(shot.get("shotNumber"), index + 1);
            boolean enabled = index == 0 || index == currentShots.size() - 1
                    || (currentShots.size() > 4 && index == currentShots.size() / 2);
            String title = defaultString(shot.get("title"), "Shot " + shotNumber);

            Map<String, Object> overlay = new LinkedHashMap<>();
            overlay.put("shotNumber", shotNumber);
            overlay.put("enabled", enabled);
            overlay.put("text", enabled ? truncate(title, 72) : "");
            overlay.put("fontFamily", "Montserrat");
            overlay.put("fontWeight", 800);
            overlay.put("fontSizePx", index == 0 ? 64 : 52);
            overlay.put("position", index == 0 ? "Upper safe zone" : "Lower safe zone");
            overlay.put("safeZone", "Keep 10% inset from all mobile edges");
            overlay.put("entrance", index == 0 ? "Wipe and fade" : "Slide up and fade");
            overlay.put("entranceDurationMs", index == 0 ? 520 : 650);
            overlay.put("delayMs", 250);
            overlay.put("holdDurationMs", 1800);
            overlay.put("exit", "Fade");
            overlay.put("exitDurationMs", 450);
            overlay.put("speed", index == 0 ? "Quick" : "Measured");
            overlay.put(
                    "rationale",
                    enabled
                            ? "Text supports comprehension while keeping the product focal point clear."
                            : "This shot remains visual-only to protect pacing and avoid overlay fatigue."
            );
            overlays.add(overlay);

            String searchable = shot.values().toString().toLowerCase();
            if (searchable.contains("chocolate") && (searchable.contains("pour") || searchable.contains("drizzle"))) {
                shot.put("action", "Reveal the finished chocolate texture in a clean macro break, then lift one piece into the hero light.");
                shot.put("visualDirection", "Use a distinct finished-product texture and consumption beat with no pouring action.");
                shot.put("revisionReason", "Replaced the repetitive chocolate-pouring visual.");
            }
            shot.put("dialogueLanguage", dialogueLanguage);
            shot.put("emojisAllowed", false);
            shot.put("detailLevel", "production_ready");
            shot.put("overlayPlan", overlay);
            revisedShots.add(shot);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("status", "deterministic_mock_ready");
        output.put("storyline", defaultString(input.get("currentStoryline"), "The revised story follows the approved client direction."));
        output.put("screenplay", defaultString(input.get("currentScreenplay"), ""));
        output.put("creativeDirection", Map.of(
                "dialogueLanguage", dialogueLanguage,
                "emojisAllowed", false,
                "detailLevel", "production_ready",
                "visualVarietyRule", "Avoid repeated chocolate-pouring imagery."
        ));
        output.put("typographySystem", typography);
        output.put("overlayPlan", overlays);
        output.put("shots", revisedShots);
        return output;
    }

    private Map<String, Object> generateLocalizedDialogue(String promptType, Map<String, Object> input) {
        String targetLanguage = defaultString(input.get("targetLanguage"), "English");
        List<Map<String, Object>> scenes = mapList(input.get("scenes")).stream()
                .map(source -> {
                    Map<String, Object> localized = new LinkedHashMap<>();
                    localized.put("id", defaultString(source.get("id"), "scene"));
                    localized.put(
                            "dialogueScript",
                            defaultString(source.get("dialogueScript"), "") + " [" + targetLanguage + "]"
                    );
                    return localized;
                })
                .toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("status", "deterministic_mock_ready");
        output.put("scenes", scenes);
        return output;
    }

    private Map<String, Object> generateCampaignAngles(String promptType, Map<String, Object> input) {
        String topic = defaultString(input.get("ideaText"), "the brief");
        List<Map<String, Object>> angles = List.of(
                campaignAngle("Problem to payoff", "Frame " + topic + " around a clear friction, then show the useful payoff.", "Start with the costly or frustrating moment.", "Fast contrast and a visible result.", "Direct-response structure makes the benefit easy to understand."),
                campaignAngle("Proof in the moment", "Make " + topic + " credible through one specific real-world use moment.", "Show the proof before explaining it.", "Close detail followed by a practical lifestyle beat.", "Demonstration earns attention without relying on claims."),
                campaignAngle("A better ritual", "Position " + topic + " as the small upgrade that improves a familiar routine.", "The ordinary routine is missing one thing.", "Premium detail, deliberate pace, and a clean final pack shot.", "It creates an emotional reason to choose the product or idea.")
        );
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("status", "deterministic_mock_ready");
        output.put("angles", angles);
        return output;
    }

    private Map<String, Object> campaignAngle(String title, String description, String hook, String visualDirection, String selectionReason) {
        Map<String, Object> angle = new LinkedHashMap<>();
        angle.put("title", title);
        angle.put("description", description);
        angle.put("hook", hook);
        angle.put("visualDirection", visualDirection);
        angle.put("selectionReason", selectionReason);
        return angle;
    }

    private Map<String, Object> generateShorts(String promptType, Map<String, Object> input) {
        int requestedShorts = Math.max(1, Math.min(50, intValue(input.get("requestedShorts"), 10)));
        int targetDuration = Math.max(30, Math.min(90, intValue(input.get("targetDurationSeconds"), 60)));
        String title = defaultString(input.get("title"), "Source video");
        String platform = defaultString(input.get("platform"), "youtube_shorts");

        List<Map<String, Object>> transcript = List.of(
                videoNode("n-001", 0, 12, "Speaker 1", "What is the strongest moment in " + title + "?", 74, 28, 84),
                videoNode("n-002", 12, 42, "Speaker 1", "The useful insight is clear enough to become a short by itself.", 86, 35, 93),
                videoNode("n-003", 42, targetDuration, "Speaker 1", "Wrap it with a takeaway that viewers can remember.", 78, 30, 88)
        );

        List<Map<String, Object>> candidates = new ArrayList<>();
        List<String> hooks = List.of("Question Hook", "Contrarian Hook", "Aha Moment", "Problem/Solution", "Reveal Hook");
        for (int index = 0; index < requestedShorts; index++) {
            int rank = index + 1;
            String hook = hooks.get(index % hooks.size());
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("title", rank == 1 ? truncate(title, 160) : truncate(title + " - short " + rank, 160));
            candidate.put("durationSeconds", targetDuration);
            candidate.put("score", Math.max(70, 96 - rank));
            candidate.put("hookType", hook);
            candidate.put("editDecisionList", Map.of(
                    "strategy", hook.contains("Problem") ? "KEEP_PROBLEM_SOLUTION_CHAIN" : "KEEP_QA_CHAIN",
                    "segments", List.of(
                            Map.of("nodeId", "n-001", "operation", "KEEP", "reason", "Context for hook."),
                            Map.of("nodeId", "n-002", "operation", "KEEP_QA_CHAIN", "reason", "Core insight."),
                            Map.of("nodeId", "n-003", "operation", "KEEP", "reason", "Payoff.")
                    )
            ));
            candidate.put("captionPlan", Map.of(
                    "style", "platform-aware",
                    "platform", platform,
                    "captions", List.of(Map.of("start", 0, "end", 3, "text", "Watch this part"))
            ));
            candidate.put("renderManifest", Map.of(
                    "renderStatus", "PENDING_REVIEW",
                    "aspectRatio", "linkedin".equals(platform) ? "4:5" : "9:16",
                    "safeZones", List.of("top_caption_safe", "bottom_ui_safe")
            ));
            candidate.put("metadata", Map.of(
                    "chain", "Q1 + best answer + insight",
                    "selectionReason", "Mock shorts agent generated a traceable candidate."
            ));
            candidates.add(candidate);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("inputHash", Integer.toHexString(input.hashCode()));
        output.put("status", "deterministic_mock_ready");
        output.put("videoDna", Map.of(
                "primaryType", "podcast",
                "confidence", 0.82,
                "structureType", "qa",
                "analysisSource", "upload_metadata"
        ));
        output.put("transcript", transcript);
        output.put("graph", Map.of(
                "graphViews", List.of("Conversation Graph", "Story Graph", "Scene Graph", "Compression Graph"),
                "nodes", transcript,
                "edges", List.of(
                        Map.of("from", "n-001", "to", "n-002", "type", "QUESTION_ANSWER", "reason", "Question leads to the best answer."),
                        Map.of("from", "n-002", "to", "n-003", "type", "CAUSE_EFFECT", "reason", "Insight needs a payoff.")
                )
        ));
        output.put("candidates", candidates);
        output.put("trace", List.of(
                Map.of("stage", "VIDEO_TYPE_CLASSIFICATION", "status", "COMPLETED", "decision", "podcast", "confidence", 0.82),
                Map.of("stage", "VIDEO_GRAPH_BUILDER", "status", "COMPLETED", "decision", "QUESTION_ANSWER", "confidence", 0.86),
                Map.of("stage", "CANDIDATE_RANKING", "status", "COMPLETED", "decision", "ranked", "confidence", 0.9)
        ));
        return output;
    }

    private Map<String, Object> videoNode(String id, int start, int end, String speaker, String transcript, int emotion, int motion, int interestingness) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("start", start);
        node.put("end", end);
        node.put("speaker", speaker);
        node.put("transcript", transcript);
        node.put("sceneId", "scene-001");
        node.put("emotion", emotion);
        node.put("motion", motion);
        node.put("interestingness", interestingness);
        node.put("frames", List.of("F01", "F02"));
        return node;
    }

    private Map<String, Object> generateShotJsonEdit(String promptType, Map<String, Object> input) {
        Map<String, Object> originalShot = mapValue(input.get("shot"));
        Map<String, Object> shot = new LinkedHashMap<>(originalShot);
        String instruction = defaultString(input.get("instruction"), "Apply a concise AI shot edit.");
        int shotNumber = intValue(shot.get("shotNumber"), 1);
        shot.put("shotNumber", shotNumber);
        shot.put("title", defaultString(shot.get("title"), "Shot " + shotNumber) + " (Edited)");
        shot.put("action", defaultString(shot.get("action"), "Updated action.") + " AI edit: " + instruction);
        shot.put("sketchPrompt", defaultString(shot.get("sketchPrompt"), defaultString(shot.get("action"), "Storyboard shot")) + " Edited instruction: " + instruction);
        shot.put("editingNotes", List.of("Mock AI edit applied: " + instruction));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("inputHash", Integer.toHexString(input.hashCode()));
        output.put("status", "deterministic_mock_ready");
        output.put("shot", shot);
        return output;
    }

    private Map<String, Object> generateWeeklyIdeaTags(String promptType, Map<String, Object> input) {
        Map<String, List<String>> topics = new LinkedHashMap<>();
        topics.put("history", List.of("Partition memory", "Forgotten queens", "Ancient India tech", "Freedom fighters", "Lost forts", "History myths"));
        topics.put("politics", List.of("Election promise check", "Youth voter mood", "Policy explainer", "Parliament moment", "Campaign strategy"));
        topics.put("sports", List.of("Cricket comeback", "Olympic prep", "Football derby", "Kabaddi grit", "Fitness challenge", "Underdog athlete"));
        topics.put("entertainment", List.of("Reality show moment", "Creator roast", "OTT twist", "Standup clip", "Meme comeback", "Award night"));
        topics.put("bollywood", List.of("Trailer decode", "Star workout", "Old song remake", "Box office clash", "Actor transformation", "Behind the scene", "Dialog trend"));

        List<Map<String, Object>> categories = new ArrayList<>();
        topics.forEach((category, titles) -> {
            List<Map<String, Object>> ideas = new ArrayList<>();
            for (String title : titles) {
                ideas.add(Map.of(
                        "title", title,
                        "prompt", "Create a short creator video about " + title + " with a sharp hook and one useful insight.",
                        "why", "Mock weekly forecast for " + category + " creator ideation.",
                        "expectedWindow", "next 7 days",
                        "confidence", "medium",
                        "sourceHint", "mock provider"
                ));
            }
            categories.add(Map.of(
                    "category", category,
                    "label", category.equals("bollywood") ? "Bollywood" : category.substring(0, 1).toUpperCase() + category.substring(1),
                    "ideas", ideas
            ));
        });

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("inputHash", Integer.toHexString(input.hashCode()));
        output.put("status", "deterministic_mock_ready");
        output.put("summary", "Mock weekly creator idea tags.");
        output.put("categories", categories);
        return output;
    }

    private Map<String, Object> generateIdeaCandidates(String promptType, Map<String, Object> input) {
        Map<String, Object> lockedBrief = mapValue(input.get("lockedBrief"));
        String title = defaultString(lockedBrief.get("title"), "Creator idea");
        String summary = defaultString(lockedBrief.get("summary"), title);
        String source = defaultString(lockedBrief.get("source"), "ORIGINAL");
        int candidateCount = Math.max(1, Math.min(20, intValue(input.get("candidateCount"), 20)));
        List<String> angles = List.of(
                "Contrarian opener",
                "Beginner mistake",
                "Before-after reveal",
                "Silent reaction",
                "POV comedy beat",
                "Two character conflict",
                "Mini tutorial",
                "Emotional payoff",
                "Shareable punchline",
                "Saved checklist"
        );

        List<Map<String, Object>> ideas = new ArrayList<>();
        for (int index = 0; index < candidateCount; index++) {
            String angle = angles.get(index % angles.size());
            ideas.add(idea(
                    "%02d. %s: %s".formatted(index + 1, angle, truncate(title, 70)),
                    "Turn \"%s\" into a %s short. %s".formatted(title, angle.toLowerCase(), truncate(summary, 180)),
                    source,
                    angle
            ));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("inputHash", Integer.toHexString(input.hashCode()));
        output.put("status", "deterministic_mock_ready");
        output.put("ideas", ideas);
        return output;
    }

    private Map<String, Object> idea(String title, String description, String source, String angle) {
        Map<String, Object> idea = new LinkedHashMap<>();
        idea.put("title", truncate(title, 180));
        idea.put("description", description);
        idea.put("hashtags", List.of("#" + source.replaceAll("[^A-Za-z0-9]", ""), "#" + angle.replaceAll("[^A-Za-z0-9]", ""), "#ShortsIdea"));
        idea.put("creativeNotes", Map.of(
                "hook", angle,
                "targetEmotion", targetEmotionFor(angle),
                "storyShape", storyShapeFor(angle),
                "selectionReason", "Generated by the mock AI provider from the locked creator brief."
        ));
        return idea;
    }

    private Map<String, Object> generateTrendInsight(String promptType, Map<String, Object> input) {
        Map<String, Object> trend = mapValue(input.get("trend"));
        Map<String, Object> postingContext = mapValue(input.get("postingContext"));
        String title = defaultString(trend.get("title"), "Selected trend");
        String category = defaultString(trend.get("categoryCode"), "creator");
        String platform = defaultString(trend.get("platformCode"), "short_form");
        String timezone = defaultString(input.get("timezone"), "Asia/Kolkata");
        BigDecimal score = confidence(decimalValue(trend.get("score"), 64d) / 100d);
        double velocity = decimalValue(trend.get("velocity"), 8d);
        long ageMinutes = Math.round(decimalValue(postingContext.get("trendAgeMinutes"), 240d));
        long minutesSinceLastSeen = Math.round(decimalValue(postingContext.get("minutesSinceLastSeen"), 30d));
        int rankingDelayMinutes = estimateRankingDelayMinutes(velocity, ageMinutes, minutesSinceLastSeen);
        List<Map<String, Object>> bestTimes = aiPostingWindows(category, platform, timezone, rankingDelayMinutes, velocity);
        String audiencePeakLabel = defaultString(bestTimes.get(0).get("label"), "AI selected peak");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("inputHash", Integer.toHexString(input.hashCode()));
        output.put("status", "deterministic_mock_ready");
        output.put("summary", title + " works because it has a clear viewer promise and can be adapted quickly by creators.");
        output.put("whyItWorked", List.of(
                "The hook is immediately understandable for the " + category + " audience.",
                velocity >= 12d
                        ? "Stored velocity is high, so the AI plan prioritizes posting before the next audience peak."
                        : "Velocity is moderate, so the AI plan favors a window with stronger audience availability over pure urgency.",
                "The timing plan accounts for platform seed-audience testing before broader ranking."
        ));
        output.put("bestTimes", bestTimes);
        output.put("creatorActions", List.of(
                "Publish " + rankingDelayMinutes + " minutes before the selected peak window.",
                "Use one simple caption that repeats the core promise.",
                "Post a second variation within 24 hours if saves or shares are strong."
        ));
        output.put("confidenceScore", score);
        output.put("evidenceType", "AI_TIMING_MODEL");
        output.put("postingStrategy", Map.of(
                "rankingDelayEstimateMinutes", rankingDelayMinutes,
                "recommendedPublishLeadMinutes", rankingDelayMinutes,
                "audiencePeakLabel", audiencePeakLabel,
                "trendUrgency", velocity >= 12d ? "HIGH" : "NORMAL",
                "decisionFactors", List.of(
                        "Platform needs time to test the post with a seed audience.",
                        "Audience availability is evaluated in " + timezone + ".",
                        "Trend freshness and velocity control how early to publish before peak."
                )
        ));
        return output;
    }

    private Map<String, Object> generateTrendPredictions(String promptType, Map<String, Object> input) {
        String category = defaultString(input.get("categoryCode"), "creator");
        String platform = defaultString(input.get("platformCode"), "instagram_reels");
        String country = defaultString(input.get("countryCode"), "IN");
        String horizonHours = defaultString(input.get("horizonHours"), "24");
        List<Map<String, Object>> recentSignals = mapList(input.get("recentSignals"));
        List<Map<String, Object>> sourceTrends = mapList(input.get("sourceTrends"));
        List<String> userSignals = stringList(input.get("userSignals"));

        List<Map<String, Object>> predictions = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> signal : recentSignals) {
            if (predictions.size() >= 4) {
                break;
            }
            String title = defaultString(signal.get("title"), category + " signal");
            predictions.add(prediction(
                    "Next " + platform + " angle: " + title,
                    "Recent source signals in " + country + " suggest creators can turn this into a short-form hook within " + horizonHours + " hours.",
                    confidence(0.86d - (index * 0.05d)),
                    "Recent connector signal was ranked for this category, so this is grounded in stored scheduler evidence.",
                    "SIGNAL_BACKED",
                    List.of(category, platform, "scheduler_signal")
            ));
            index++;
        }

        for (Map<String, Object> trend : sourceTrends) {
            if (predictions.size() >= 6) {
                break;
            }
            String title = defaultString(trend.get("title"), category + " trend");
            predictions.add(prediction(
                    "Follow-up format: " + title,
                    "Existing saved trend momentum can be extended with a sharper hook, faster opening, and creator-specific point of view.",
                    confidence(0.78d - (predictions.size() * 0.04d)),
                    "This uses stored category trend history supplied to the prediction prompt.",
                    "TREND_HISTORY_BACKED",
                    List.of(category, platform, "trend_history")
            ));
        }

        for (String userSignal : userSignals) {
            if (predictions.size() >= 7) {
                break;
            }
            predictions.add(prediction(
                    "Creator prompt angle: " + truncate(userSignal, 80),
                    "User-provided context can become a niche short-form experiment if paired with the category's active audience behavior.",
                    confidence(0.64d),
                    "This is based on creator-provided context, not independent source evidence.",
                    "USER_CONTEXT",
                    List.of(category, platform, "creator_context")
            ));
        }

        if (predictions.isEmpty()) {
            predictions.add(prediction(
                    category + " relatable before-after story",
                    "A simple before-after creator story is likely to work because the category repeatedly rewards visible change and immediate payoff.",
                    confidence(0.46d),
                    "No recent stored source evidence was available, so this is a lower-confidence heuristic based on recurring short-form behavior.",
                    "HEURISTIC",
                    List.of(category, platform, "heuristic")
            ));
            predictions.add(prediction(
                    category + " mistake-to-fix mini lesson",
                    "A fast mistake/fix structure can convert broad audience pain into a practical reel or short.",
                    confidence(0.41d),
                    "This uses general category memory and common audience-learning cycles, not fresh connector data.",
                    "HEURISTIC",
                    List.of(category, platform, "heuristic")
            ));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("inputHash", Integer.toHexString(input.hashCode()));
        output.put("status", "deterministic_mock_ready");
        output.put("evidenceSignalCount", recentSignals.size());
        output.put("sourceTrendCount", sourceTrends.size());
        output.put("predictions", predictions);
        return output;
    }

    private Map<String, Object> prediction(
            String title,
            String summary,
            BigDecimal confidenceScore,
            String rationale,
            String evidenceType,
            List<String> tags
    ) {
        Map<String, Object> prediction = new LinkedHashMap<>();
        prediction.put("title", truncate(title, 180));
        prediction.put("summary", summary);
        prediction.put("confidenceScore", confidenceScore);
        prediction.put("rationale", rationale);
        prediction.put("evidenceType", evidenceType);
        prediction.put("suggestedTags", tags);
        return prediction;
    }

    private BigDecimal confidence(double value) {
        return BigDecimal.valueOf(Math.max(0.15d, Math.min(0.95d, value)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private double decimalValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String defaultString(Object value, String defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private int estimateRankingDelayMinutes(double velocity, long ageMinutes, long minutesSinceLastSeen) {
        int baseDelay = velocity >= 18d ? 35 : velocity >= 12d ? 50 : 75;
        int stalePenalty = minutesSinceLastSeen > 240 ? 20 : 0;
        int maturityAdjustment = ageMinutes > 2880 ? -10 : 0;
        return Math.max(25, Math.min(120, baseDelay + stalePenalty + maturityAdjustment));
    }

    private List<Map<String, Object>> aiPostingWindows(String category, String platform, String timezone, int rankingDelayMinutes, double velocity) {
        String urgencyReason = velocity >= 12d
                ? "Velocity is high, so publish before the audience peak and let the platform ranking test warm up."
                : "Velocity is moderate, so choose the strongest availability block and allow ranking warm-up.";
        return List.of(
                postingWindow("AI primary peak", "6PM - 10PM", timezone, urgencyReason + " Recommended lead: " + rankingDelayMinutes + " minutes."),
                postingWindow("AI secondary test", "12PM - 2PM", timezone, "Secondary availability window for early save/share testing on " + platform + "."),
                postingWindow("AI niche window", "10PM - 12AM", timezone, "Useful for niche " + category + " audiences when evening competition is high.")
        );
    }

    private Map<String, Object> postingWindow(String label, String window, String timezone, String reason) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("label", label);
        slot.put("window", window);
        slot.put("timezone", timezone);
        slot.put("reason", reason);
        return slot;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    private String targetEmotionFor(String angle) {
        String normalized = angle.toLowerCase();
        if (normalized.contains("comedy") || normalized.contains("punchline")) {
            return "Relatable humor";
        }
        if (normalized.contains("emotional")) {
            return "Honest connection";
        }
        if (normalized.contains("tutorial") || normalized.contains("checklist")) {
            return "Clarity and usefulness";
        }
        return "Fast curiosity";
    }

    private String storyShapeFor(String angle) {
        String normalized = angle.toLowerCase();
        if (normalized.contains("before-after")) {
            return "before / after reveal";
        }
        if (normalized.contains("conflict")) {
            return "two-character tension and resolution";
        }
        if (normalized.contains("tutorial") || normalized.contains("checklist")) {
            return "quick practical lesson";
        }
        if (normalized.contains("reaction")) {
            return "reaction first, explanation second";
        }
        return "hook, twist, payoff";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
