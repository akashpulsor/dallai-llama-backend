package com.dalai.llama.creativeplanning.dto;

import java.util.UUID;

public record ProductReferenceImageView(
        UUID id,
        UUID productProfileId,
        String bucket,
        String objectKey,
        String signedUrl,
        ReferenceImageAnalysisView analysis
) {
}
