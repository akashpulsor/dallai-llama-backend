package com.dalai.llama.videogen.dto;

import java.util.List;

public record GenerateBatchResponse(
        List<GenerateShotResponse> results
) {
}
