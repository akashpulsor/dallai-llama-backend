package com.dalai.llama.llmgateway.controller;

import com.dalai.llama.llmgateway.dto.prompt.PromptDtos;
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

    private final ProviderPromptStrategyResolver strategyResolver;

    @PostMapping("/v1/prompt/format")
    public ResponseEntity<PromptDtos.PromptFormatResponse> format(@Valid @RequestBody PromptDtos.PromptFormatRequest request) {
        long startMs = System.currentTimeMillis();
        ProviderPromptStrategy strategy = strategyResolver.resolve(request.modelId());
        ProviderPromptStrategy.Built built = strategy.build(request.shotContext(), request.flags());
        int positiveLen = built.positive() == null ? 0 : built.positive().length();
        int negativeLen = built.negative() == null ? 0 : built.negative().length();
        log.info("prompt-format modelId={} strategy={} positiveLen={} negativeLen={} maxLen={} elapsedMs={}",
                request.modelId(), strategy.getClass().getSimpleName(), positiveLen, negativeLen,
                strategy.maxPromptLength(), System.currentTimeMillis() - startMs);
        return ResponseEntity.ok(new PromptDtos.PromptFormatResponse(
                built.positive(), built.negative(), strategy.maxPromptLength()));
    }

    /** Lightweight query used by callers that only need the compression threshold without
     * running the full format pass. */
    @GetMapping("/v1/prompt/max-length")
    public ResponseEntity<MaxLengthResponse> maxLength(@RequestParam String modelId) {
        return ResponseEntity.ok(new MaxLengthResponse(strategyResolver.resolve(modelId).maxPromptLength()));
    }

    public record MaxLengthResponse(int maxLength) {}
}
