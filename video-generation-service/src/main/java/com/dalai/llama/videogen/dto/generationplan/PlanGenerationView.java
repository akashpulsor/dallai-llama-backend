package com.dalai.llama.videogen.dto.generationplan;

import com.dalai.llama.videogen.dto.VideoGenJobView;

import java.util.UUID;

/** What Generate Video started: the job to poll, and the prompt row holding the exact text sent. */
public record PlanGenerationView(
        UUID promptId,
        VideoGenJobView job
) {
}
