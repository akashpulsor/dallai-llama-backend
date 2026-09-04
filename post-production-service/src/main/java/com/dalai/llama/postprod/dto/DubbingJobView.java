package com.dalai.llama.postprod.dto;

import java.util.UUID;

public record DubbingJobView(
        UUID jobId,
        String targetLanguage,
        String status,
        String transcript,
        String translatedTranscript,
        String lastError,
        // Presigned URL of the dubbed video once the job COMPLETED (null while it is still
        // PENDING/PROCESSING or if it FAILED). Lets a poller pick up the result without the
        // separate 302 /video hop -- see DubbingOrchestrator.toView.
        String videoUrl
) {
}
