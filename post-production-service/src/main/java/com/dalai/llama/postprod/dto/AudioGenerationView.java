package com.dalai.llama.postprod.dto;

public record AudioGenerationView(
        String modelUsed,
        String audioUrl
) {
}
