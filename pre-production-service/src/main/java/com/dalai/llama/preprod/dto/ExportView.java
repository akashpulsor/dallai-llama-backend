package com.dalai.llama.preprod.dto;

public record ExportView(
        String bucket,
        String objectKey,
        String signedUrl
) {
}
