package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.DraftStatus;

import java.util.List;
import java.util.UUID;

public record ScreenplayView(
        UUID id,
        UUID projectId,
        UUID scriptId,
        DraftStatus status,
        List<ScreenplaySceneView> scenes
) {
}
