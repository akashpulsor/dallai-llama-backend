package com.dalai.llama.creator.dto.request;

import java.util.Map;

public record ShortCandidateReviewRequest(
        String action,
        Map<String, Object> payload
) {
}