package com.dalai.llama.videogen.service.llmgateway;

/** Wire-mirror of llm-gateway's {@code PromptDtos.PromptFormatResponse} -- the composed
 * positive/negative prompt plus the resolved strategy's max prompt length so this service's
 * compression stage can decide whether to compress without a second round-trip. */
public record LlmGatewayPromptFormatResponse(
        String positive,
        String negative,
        int maxPromptLength
) {
}
