package com.dalai.llama.creativeplanning.dto;

import java.util.UUID;

/** {@code analysis} is null until the requirement is funded -- see {@code
 * ReferenceMaterialAnalysisService}. */
public record ProjectReferenceImageView(
        UUID id,
        UUID projectRequirementId,
        String bucket,
        String objectKey,
        String signedUrl,
        ReferenceImageAnalysisView analysis
) {
}
