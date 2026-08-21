package com.dalai.llama.creativeplanning.dto;

public record BrandPlanExportView(
        String bucket,
        String objectKey,
        String signedUrl
) {
}
