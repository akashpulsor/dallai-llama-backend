package com.dalai.llama.preprod.dto;

public record StoryboardImageView(
        String bucket,
        String objectKey,
        String signedUrl
) {
}
