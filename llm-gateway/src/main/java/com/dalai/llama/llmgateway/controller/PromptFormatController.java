package com.dalai.llama.llmgateway.controller;

import com.dalai.llama.llmgateway.dto.prompt.PromptDtos;
import com.dalai.llama.llmgateway.service.PromptFormatService;
import com.dalai.llama.llmgateway.service.prompt.ProviderPromptStrategy;
import com.dalai.llama.llmgateway.service.prompt.ProviderPromptStrategyResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prompt-formatting endpoint. Video-generation-service sends raw shot data + a modelId, this
 * controller resolves the model-specific {@link ProviderPromptStrategy} (Seedance / Wan / default),
 * composes the positive+negative prompt, and returns both plus the strategy's max prompt length
 * so the caller's compression stage can decide next steps without a second round-trip. All
 * per-model prompt-shape knowledge lives on this side of the wire now.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PromptFormatController {

    private final PromptFormatService promptFormatService;

    @PostMapping("/v1/prompt/format")
    public ResponseEntity<PromptDtos.PromptFormatResponse> format(
            @Valid @RequestBody PromptDtos.PromptFormatRequest request) {

        return ResponseEntity.ok(promptFormatService.format(request));
    }

    @GetMapping("/v1/prompt/max-length")
    public ResponseEntity<MaxLengthResponse> maxLength(@RequestParam String modelId) {
        return ResponseEntity.ok(
                new MaxLengthResponse(promptFormatService.maxPromptLength(modelId))
        );
    }

    public record MaxLengthResponse(int maxLength) {}
}
