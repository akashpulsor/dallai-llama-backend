package com.dalai.llama.trendintel.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code industry}/{@code targetAudience} are optional narrowing context -- a bare topic alone
 * is a valid request ("what's trending in short-form video right now"). */
public record GenerateTrendReportRequest(
        @NotBlank String topic,
        String industry,
        String targetAudience
) {
}
