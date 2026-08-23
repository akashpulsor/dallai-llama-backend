package com.dalai.llama.preprod.dto;

public record ShotPlanIssueView(
        String shotRef,
        String category,
        String message
) {
}
