package com.dalai.llama.creativeplanning.dto;

import java.util.UUID;

public record ProjectRequirementAttachmentView(
        UUID id,
        String bucket,
        String objectKey,
        String signedUrl
) {
}
