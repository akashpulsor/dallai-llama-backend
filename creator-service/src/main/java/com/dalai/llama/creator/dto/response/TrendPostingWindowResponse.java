package com.dalai.llama.creator.dto.response;

public record TrendPostingWindowResponse(
        String label,
        String window,
        String timezone,
        String reason
) {
}
