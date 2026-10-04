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
        List<SourceTemporalActionView> timeline,
        List<ValidationIssueView> timelineIssues,
        VideoPromptDraftView prompt,
        ContinuationFrameView continuationFrame,
        UUID submittedJobId,
        OffsetDateTime submittedAt
) {
}
