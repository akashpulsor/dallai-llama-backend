package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.dto.prompt.PromptDtos;
import com.dalai.llama.llmgateway.service.prompt.ProviderPromptStrategy;
import com.dalai.llama.llmgateway.service.prompt.ProviderPromptStrategyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromptFormatService {

    private final ProviderPromptStrategyResolver strategyResolver;

    public PromptDtos.PromptFormatResponse format(PromptDtos.PromptFormatRequest request) {
        long startMs = System.currentTimeMillis();

        ProviderPromptStrategy strategy = strategyResolver.resolve(request.modelId());
        ProviderPromptStrategy.Built built = strategy.build(request.shotContext(), request.flags());

        int positiveLen = built.positive() == null ? 0 : built.positive().length();
        int negativeLen = built.negative() == null ? 0 : built.negative().length();

        log.info("prompt-format modelId={} strategy={} positiveLen={} negativeLen={} maxLen={} elapsedMs={}",
                request.modelId(),
                strategy.getClass().getSimpleName(),
                positiveLen,
                negativeLen,
                strategy.maxPromptLength(),
                System.currentTimeMillis() - startMs);

        return new PromptDtos.PromptFormatResponse(
                built.positive(),
                built.negative(),
                strategy.maxPromptLength()
        );
    }

    public int maxPromptLength(String modelId) {
        return strategyResolver.resolve(modelId).maxPromptLength();
    }
}