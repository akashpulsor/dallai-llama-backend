package com.dalai.llama.videogen.service.generationplan;

import com.dalai.llama.videogen.dto.generationplan.ActionCoverageView;
import com.dalai.llama.videogen.dto.generationplan.RequiredActionView;
import com.dalai.llama.videogen.dto.generationplan.SourceTemporalActionView;
import com.dalai.llama.videogen.dto.generationplan.ValidationIssueView;
import com.dalai.llama.videogen.dto.generationplan.VideoDurationAssessmentView;
import com.dalai.llama.videogen.dto.generationplan.VideoModelCapabilitiesView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic checks on everything a model hands back, and on the creator's own choices.
 *
 * <p>A model saying it covered every action is not evidence that it did. These checks are what can
 * be proven from structure alone -- every identifier present, none invented, order kept, the
 * timeline continuous from zero to the chosen duration. They cannot prove the prose of a prompt
 * means what the plan means; that is what the separate prompt review is for.
 *
 * <p>Seconds are compared at millisecond precision: 3.0 and 3.000 are the same instant, and a
 * model's 2.9999 is not a gap worth reporting.
 */
public final class GenerationPlanValidator {

    /** A fixed-length line may lose this much to rounding before it counts as shortened. */
    private static final BigDecimal FIXED_TOLERANCE = new BigDecimal("0.050");
    private static final Pattern SECONDS_MENTION =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:-\\s*)?(?:s|sec|secs|second|seconds)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POST_PRODUCTION_TERMS =
            Pattern.compile("\\b(slow[- ]?motion|interpolat\\w*|retim\\w*|frame duplication|speed ramp\\w*)\\b", Pattern.CASE_INSENSITIVE);

    private GenerationPlanValidator() {
    }

    // ---------------------------------------------------------------- settings

    public static List<ValidationIssueView> settingsErrors(VideoModelCapabilitiesView capabilities,
                                                           Integer durationSeconds, Integer fps) {
        List<ValidationIssueView> errors = new ArrayList<>();
        if (durationSeconds == null) {
            errors.add(issue("DURATION_NOT_SELECTED", null, "Choose a generation duration."));
        } else if (!capabilities.supportedDurationsSeconds().contains(durationSeconds)) {
            errors.add(issue("UNSUPPORTED_DURATION", null, "%s cannot generate %ds; it takes %s."
                    .formatted(capabilities.modelId(), durationSeconds, durationRange(capabilities))));
        }
        if (!capabilities.supportedFps().isEmpty()) {
            if (fps == null) {
                errors.add(issue("FPS_NOT_SELECTED", null, "Choose a generation frame rate."));
            } else if (!capabilities.supportedFps().contains(fps)) {
                errors.add(issue("UNSUPPORTED_FPS", null, "%s renders at %s fps, not %d."
                        .formatted(capabilities.modelId(), capabilities.supportedFps(), fps)));
            }
        }
        return errors;
    }

    /** The creator's call to make, so a warning and never an error. */
    public static List<ValidationIssueView> settingsWarnings(Integer durationSeconds,
                                                             VideoDurationAssessmentView assessment) {
        List<ValidationIssueView> warnings = new ArrayList<>();
        if (durationSeconds != null && assessment != null && assessment.minimumViableDurationSeconds() != null
                && seconds(durationSeconds).compareTo(ms(assessment.minimumViableDurationSeconds())) < 0) {
            warnings.add(issue("BELOW_MINIMUM_VIABLE", null,
                    "%ds is shorter than the %ss the assessment found every planned action needs. Choose a longer duration, or re-analyse if the plan changed."
                            .formatted(durationSeconds, ms(assessment.minimumViableDurationSeconds()).stripTrailingZeros().toPlainString())));
        }
        return warnings;
    }

    // ---------------------------------------------------------------- assessment

    public static List<ValidationIssueView> assessment(List<RequiredActionView> actions,
                                                       VideoDurationAssessmentView assessment,
                                                       VideoModelCapabilitiesView capabilities) {
        List<ValidationIssueView> issues = new ArrayList<>();
        if (assessment == null) {
            return issues;
        }
        Integer recommended = assessment.recommendedDurationSeconds();
        if (recommended == null || recommended <= 0) {
            issues.add(issue("MISSING_RECOMMENDATION", null, "The assessment did not recommend a duration."));
        } else if (!capabilities.supportedDurationsSeconds().contains(recommended)) {
            issues.add(issue("UNSUPPORTED_DURATION", null, "The assessment recommended %ds, which %s cannot generate (%s)."
                    .formatted(recommended, capabilities.modelId(), durationRange(capabilities))));
        }
        Integer fps = assessment.recommendedGenerationFps();
        if (!capabilities.supportedFps().isEmpty() && (fps == null || !capabilities.supportedFps().contains(fps))) {
            issues.add(issue("UNSUPPORTED_FPS", null, "The assessment recommended %s fps; %s renders at %s fps."
                    .formatted(fps, capabilities.modelId(), capabilities.supportedFps())));
        }
        BigDecimal minimum = assessment.minimumViableDurationSeconds();
        if (minimum != null && recommended != null && ms(minimum).compareTo(seconds(recommended)) > 0) {
            issues.add(issue("INCONSISTENT_MINIMUM", null, "The minimum viable duration (%ss) is longer than the recommended %ds."
                    .formatted(ms(minimum).stripTrailingZeros().toPlainString(), recommended)));
        }

        List<ActionCoverageView> coverage = assessment.actionCoverage() == null ? List.of() : assessment.actionCoverage();
        Map<String, RequiredActionView> byId = index(actions);
        Map<String, ActionCoverageView> covered = new HashMap<>();
        for (ActionCoverageView entry : coverage) {
            if (!byId.containsKey(entry.actionId())) {
                issues.add(issue("UNKNOWN_ACTION", entry.actionId(), "The assessment timed an action the plan does not contain."));
                continue;
            }
            if (covered.putIfAbsent(entry.actionId(), entry) != null) {
                issues.add(issue("DUPLICATE_ACTION", entry.actionId(), "The assessment timed this action twice."));
            }
            if (!entry.preserved()) {
                issues.add(issue("ACTION_NOT_PRESERVED", entry.actionId(), "The assessment says this action is not preserved."));
            }
            if (recommended != null) {
                timestamps(issues, entry.actionId(), entry.startSeconds(), entry.endSeconds(), seconds(recommended));
            }
        }
        for (RequiredActionView action : actions) {
            if (!covered.containsKey(action.actionId())) {
                issues.add(issue("MISSING_ACTION", action.actionId(), "The assessment did not account for this action."));
            }
        }
        // Order: an action never starts before the one it depends on.
        for (RequiredActionView action : actions) {
            ActionCoverageView mine = covered.get(action.actionId());
            ActionCoverageView dependency = action.dependsOnActionId() == null ? null : covered.get(action.dependsOnActionId());
            if (mine != null && dependency != null && mine.startSeconds() != null && dependency.startSeconds() != null
                    && ms(mine.startSeconds()).compareTo(ms(dependency.startSeconds())) < 0) {
                issues.add(issue("ORDER_VIOLATED", action.actionId(),
                        "Starts before %s, which the plan puts first.".formatted(action.dependsOnActionId())));
            }
            if (mine != null && action.fixedSeconds() != null
                    && length(mine.startSeconds(), mine.endSeconds()).add(FIXED_TOLERANCE).compareTo(action.fixedSeconds()) < 0) {
                issues.add(issue("FIXED_TIMING_SHORTENED", action.actionId(), "Given %ss; the line takes %ss to say."
                        .formatted(plain(length(mine.startSeconds(), mine.endSeconds())), plain(action.fixedSeconds()))));
            }
        }
        ActionCoverageView open = covered.get(RequiredActionExtractor.OPEN);
        if (open != null && open.startSeconds() != null && ms(open.startSeconds()).signum() != 0) {
            issues.add(issue("OPENING_STATE_NOT_AT_START", RequiredActionExtractor.OPEN, "The opening state must be at 0s."));
        }
        ActionCoverageView end = covered.get(RequiredActionExtractor.END);
        if (end != null && recommended != null && end.endSeconds() != null && ms(end.endSeconds()).compareTo(seconds(recommended)) != 0) {
            issues.add(issue("ENDING_STATE_NOT_AT_END", RequiredActionExtractor.END,
                    "The final state must be reached at %ds, the recommended duration.".formatted(recommended)));
        }
        return issues;
    }

    // ---------------------------------------------------------------- timeline

    public static List<ValidationIssueView> timeline(List<RequiredActionView> actions,
                                                     List<SourceTemporalActionView> intervals,
                                                     Integer durationSeconds) {
        List<ValidationIssueView> issues = new ArrayList<>();
        if (intervals == null || intervals.isEmpty() || durationSeconds == null) {
            return issues;
        }
        BigDecimal duration = seconds(durationSeconds);
        SourceTemporalActionView first = intervals.get(0);
        SourceTemporalActionView last = intervals.get(intervals.size() - 1);
        if (first.startSeconds() == null || ms(first.startSeconds()).signum() != 0) {
            issues.add(issue("TIMELINE_NOT_FROM_ZERO", null, "The timeline starts at %ss, not 0s.".formatted(plain(first.startSeconds()))));
        }
        if (last.endSeconds() == null || ms(last.endSeconds()).compareTo(duration) != 0) {
            issues.add(issue("TIMELINE_NOT_TO_END", null, "The timeline ends at %ss, not at the selected %ds."
                    .formatted(plain(last.endSeconds()), durationSeconds)));
        }
        for (int i = 0; i < intervals.size(); i++) {
            SourceTemporalActionView interval = intervals.get(i);
            timestamps(issues, interval.actionId(), interval.startSeconds(), interval.endSeconds(), duration);
            if (i > 0 && interval.startSeconds() != null && intervals.get(i - 1).endSeconds() != null) {
                int gap = ms(interval.startSeconds()).compareTo(ms(intervals.get(i - 1).endSeconds()));
                if (gap > 0) {
                    issues.add(issue("GAP", interval.actionId(), "Nothing is planned between %ss and %ss."
                            .formatted(plain(intervals.get(i - 1).endSeconds()), plain(interval.startSeconds()))));
                } else if (gap < 0) {
                    issues.add(issue("OVERLAP", interval.actionId(), "Overlaps the interval before it at %ss."
                            .formatted(plain(interval.startSeconds()))));
                }
            }
        }

        Map<String, RequiredActionView> byId = index(actions);
        Map<String, Integer> firstSeen = new HashMap<>();
        Map<String, BigDecimal> secondsGiven = new HashMap<>();
        Set<String> reported = new HashSet<>();
        for (int i = 0; i < intervals.size(); i++) {
            SourceTemporalActionView interval = intervals.get(i);
            String id = interval.actionId();
            if (!byId.containsKey(id)) {
                if (reported.add(id)) {
                    issues.add(issue("UNKNOWN_ACTION", id, "The timeline names an action the plan does not contain."));
                }
                continue;
            }
            firstSeen.putIfAbsent(id, i);
            secondsGiven.merge(id, length(interval.startSeconds(), interval.endSeconds()), BigDecimal::add);
        }
        for (RequiredActionView action : actions) {
            Integer at = firstSeen.get(action.actionId());
            if (at == null) {
                issues.add(issue("MISSING_ACTION", action.actionId(), "The timeline leaves this action out."));
                continue;
            }
            Integer dependencyAt = action.dependsOnActionId() == null ? null : firstSeen.get(action.dependsOnActionId());
            if (dependencyAt != null && at < dependencyAt) {
                issues.add(issue("ORDER_VIOLATED", action.actionId(),
                        "Appears before %s, which the plan puts first.".formatted(action.dependsOnActionId())));
            }
            if (action.fixedSeconds() != null
                    && secondsGiven.get(action.actionId()).add(FIXED_TOLERANCE).compareTo(action.fixedSeconds()) < 0) {
                issues.add(issue("FIXED_TIMING_SHORTENED", action.actionId(), "Given %ss; the line takes %ss to say."
                        .formatted(plain(secondsGiven.get(action.actionId())), plain(action.fixedSeconds()))));
            }
        }
        if (!RequiredActionExtractor.OPEN.equals(first.actionId())) {
            issues.add(issue("OPENING_STATE_NOT_FIRST", RequiredActionExtractor.OPEN, "The first interval must carry the opening state."));
        }
        if (!RequiredActionExtractor.END.equals(last.actionId())) {
            issues.add(issue("ENDING_STATE_NOT_LAST", RequiredActionExtractor.END, "The last interval must reach the final state."));
        }
        return issues;
    }

    // ---------------------------------------------------------------- prompt

    /** Blocks generation: the provider would refuse or truncate. */
    public static List<ValidationIssueView> promptErrors(String prompt, int maxChars) {
        List<ValidationIssueView> errors = new ArrayList<>();
        if (prompt == null || prompt.isBlank()) {
            errors.add(issue("EMPTY_PROMPT", null, "The prompt is empty."));
        } else if (prompt.length() > maxChars) {
            errors.add(issue("OVER_CHARACTER_LIMIT", null, "%d characters; the model accepts %d."
                    .formatted(prompt.length(), maxChars)));
        }
        return errors;
    }

    /** Worth a look, never blocking: the creator may mean exactly what they wrote. */
    public static List<ValidationIssueView> promptWarnings(String prompt, Integer generationDurationSeconds) {
        List<ValidationIssueView> warnings = new ArrayList<>();
        if (prompt == null || prompt.isBlank()) {
            return warnings;
        }
        Matcher seconds = SECONDS_MENTION.matcher(prompt);
        boolean anyTimestamp = false;
        Set<String> beyond = new HashSet<>();
        while (seconds.find()) {
            anyTimestamp = true;
            BigDecimal value = new BigDecimal(seconds.group(1));
            if (generationDurationSeconds != null && value.compareTo(seconds(generationDurationSeconds)) > 0) {
                beyond.add(value.stripTrailingZeros().toPlainString());
            }
        }
        if (!anyTimestamp) {
            warnings.add(issue("NO_TIMESTAMPS", null, "The prompt has no inline timestamps, so the model will time the action itself."));
        }
        for (String value : beyond) {
            warnings.add(issue("TIME_BEYOND_DURATION", null, "Mentions %ss, past the %ds being generated."
                    .formatted(value, generationDurationSeconds)));
        }
        Matcher post = POST_PRODUCTION_TERMS.matcher(prompt);
        if (post.find()) {
            warnings.add(issue("POST_PRODUCTION_INSTRUCTION", null,
                    "\"%s\" is a post-production instruction; the source clip is generated at normal speed."
                            .formatted(post.group(1).toLowerCase(Locale.ROOT))));
        }
        return warnings;
    }

    // ---------------------------------------------------------------- helpers

    private static void timestamps(List<ValidationIssueView> issues, String actionId,
                                   BigDecimal start, BigDecimal end, BigDecimal duration) {
        if (start == null || end == null) {
            issues.add(issue("INVALID_TIMESTAMP", actionId, "Missing a start or end time."));
            return;
        }
        if (ms(start).signum() < 0 || ms(end).compareTo(ms(start)) <= 0 || ms(end).compareTo(duration) > 0) {
            issues.add(issue("INVALID_TIMESTAMP", actionId, "%ss-%ss does not fit inside 0-%ss."
                    .formatted(plain(start), plain(end), plain(duration))));
        }
    }

    private static Map<String, RequiredActionView> index(List<RequiredActionView> actions) {
        Map<String, RequiredActionView> byId = new HashMap<>();
        actions.forEach(action -> byId.put(action.actionId(), action));
        return byId;
    }

    private static String durationRange(VideoModelCapabilitiesView capabilities) {
        List<Integer> durations = capabilities.supportedDurationsSeconds();
        return durations.isEmpty() ? "no known durations"
                : "%d-%ds".formatted(durations.get(0), durations.get(durations.size() - 1));
    }

    private static BigDecimal length(BigDecimal start, BigDecimal end) {
        return start == null || end == null ? BigDecimal.ZERO : ms(end).subtract(ms(start));
    }

    static BigDecimal ms(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal seconds(int value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "?" : ms(value).stripTrailingZeros().toPlainString();
    }

    private static ValidationIssueView issue(String code, String actionId, String message) {
        return new ValidationIssueView(code, actionId, message);
    }
}
