package com.dalai.llama.preprod.dto;

import java.util.UUID;

public record ExportView(
        UUID id,
        String bucket,
        String objectKey,
        String signedUrl
) {
}
