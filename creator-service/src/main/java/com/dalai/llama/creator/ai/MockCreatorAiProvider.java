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

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("provider", providerName());
        output.put("promptType", promptType);
        output.put("inputHash", Integer.toHexString(input.hashCode()));
        output.put("status", "deterministic_mock_ready");
        return output;
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

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
