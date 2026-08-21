package com.dalai.llama.postprod.dto;

import java.util.UUID;

public record DubbingJobView(
        UUID jobId,
        String targetLanguage,
        String status,
        String transcript,
        String translatedTranscript,
        String lastError
) {
}
