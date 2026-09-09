package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.ShotImageKind;

import java.util.UUID;

public record ShotImageRef(
        UUID shotId,
        UUID imageId,
        ShotImageKind kind

) {
}
