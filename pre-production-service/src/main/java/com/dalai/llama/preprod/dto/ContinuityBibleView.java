package com.dalai.llama.preprod.dto;

import java.util.List;

public record ContinuityBibleView(
        String negativePrompt,
        List<ContinuityLockView> locks
) {
    public record ContinuityLockView(
            String category,
            String value
    ) {
    }
}
