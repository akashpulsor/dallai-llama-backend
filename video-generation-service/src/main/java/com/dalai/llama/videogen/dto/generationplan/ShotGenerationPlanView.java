package com.dalai.llama.videogen.dto.generationplan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Everything the video studio shows for one shot. Each section stands alone: an assessment that
 * failed validation does not stop the creator choosing settings, building a timeline or writing a
 * prompt themselves.
 *
 * <p>Nothing here says "stale". The settings each artefact was built for are returned beside the
 * current settings, and the page compares them.
 */
public record ShotGenerationPlanView(
        UUID planId,
        UUID projectId,
        UUID shotId,
        String shotRef,
        String modelId,
        BigDecimal plannedDurationSeconds,
        VideoModelCapabilitiesView capabilities,
        List<RequiredActionView> requiredActions,
        VideoDurationAssessmentView assessment,
        List<ValidationIssueView> assessmentIssues,
        VideoGenerationSettingsView settings,
        Integer timelineDurationSeconds,
        Integer timelineFps,
        OffsetDateTime timelineBuiltAt,
        List<SourceTemporalActionView> timeline,
        List<ValidationIssueView> timelineIssues,
        /** Seconds of the clip one second of the shot takes: below 1 when a shorter clip is slowed to
         * the shot afterwards. Fixed lengths in {@code timelineIssues} are in clip seconds by it. */
        BigDecimal clipSecondsPerShotSecond,
        VideoPromptDraftView prompt,
        ContinuationFrameView continuationFrame,
        UUID submittedJobId,
        OffsetDateTime submittedAt
) {
}
