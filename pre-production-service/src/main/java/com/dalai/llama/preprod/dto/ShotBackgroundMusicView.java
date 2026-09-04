package com.dalai.llama.preprod.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ShotBackgroundMusicView(
        UUID id,
        String signedUrl,
        String prompt,
        OffsetDateTime createdAt
) {
}
