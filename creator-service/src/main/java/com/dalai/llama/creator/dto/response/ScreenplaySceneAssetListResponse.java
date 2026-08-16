package com.dalai.llama.creator.dto.response;

import java.util.List;

public record ScreenplaySceneAssetListResponse(
        List<ScreenplaySceneAssetResponse> scenes,
        ScreenplaySceneAssetResponse combinedVideo
) {
}
