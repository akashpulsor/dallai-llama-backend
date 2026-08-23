package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ShotImageKind;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ShotImageView(
        UUID id,
        ShotImageKind kind,
        String bucket,
        String objectKey,
        String signedUrl,
        OffsetDateTime createdAt
) {
}
