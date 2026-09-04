package com.dalai.llama.llmgateway.service.provider;

import com.dalai.llama.llmgateway.dto.ChatMessage;
import com.dalai.llama.llmgateway.dto.ToolCall;
import com.dalai.llama.llmgateway.dto.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Direct Gemini adapter (doc §7: "Google -- direct API access, one adapter, one credential set
 * per environment"). Reuses the GOOGLE_API_KEY already provisioned for ai-service/creator-service
 * -- no new secret needed for v1.
 *
 * <p>Tool calling: {@code request.tools()} maps to Gemini's {@code tools[].functionDeclarations},
 * a model-requested call maps back from {@code candidates[0].content.parts[].functionCall}, and
 * an inbound {@code role="tool"} message (the caller's tool result) maps to Gemini's
 * {@code role="function"} / {@code functionResponse} part. This adapter never executes a tool --
 * it only translates the wire format.
 */
@Slf4j
@Component
public class GoogleGeminiProvider implements LlmProvider {

    private final WebClient webClient;
    private final String apiKey;
    private final int defaultTimeoutMs;

    public GoogleGeminiProvider(
            @Value("${llm-gateway.google.base-url}") String baseUrl,
            @Value("${llm-gateway.google.api-key}") String apiKey,
            @Value("${llm-gateway.google.timeout-ms}") int defaultTimeoutMs
    ) {
        this.apiKey = apiKey;
        this.defaultTimeoutMs = defaultTimeoutMs;
        // Spring WebFlux defaults to a 256KB in-memory response buffer -- a real base64-encoded
        // generated image (this provider's whole reason for existing, for image-typed models like
        // gemini-2.5-flash-image) routinely exceeds that, so the default silently produces an
        // empty/truncated body instead of the real response. 16MB matches creator-service's own
        // GoogleGenAiClientFactory, which hit and fixed this exact problem already.
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
    }

    @Override
    public String providerId() {
        return "google";
    }

    @Override
    public Mono<LlmResponse> generate(CanonicalRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new LlmProviderException("GOOGLE_API_KEY is not configured", false));
        }
        if ("embedding".equals(request.modelType())) {
            return generateEmbedding(request);
        }
        Map<String, Object> body = toGeminiRequestBody(request);
        String path = "/v1beta/models/%s:generateContent?key=%s".formatted(request.modelId(), apiKey);
        // defaultTimeoutMs, not a second hardcoded literal -- this used to ignore the injected
        // config value entirely and always time out at a hardcoded 30s regardless of what
        // llm-gateway.google.timeout-ms was set to, which is exactly what tripped on a shot-list
        // generation call: a long/detailed prompt (many shots, the full per-shot editing/
        // continuity + cinematography breakdown) routinely needs more than 30s from Gemini, and
        // every caller into llm-gateway already budgets 120s for the whole round trip, so there
        // was no reason this inner leg was the tightest link in the chain.
        int timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : defaultTimeoutMs;
        return webClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(this::toLlmResponse)
                .onErrorMap(WebClientResponseException.class, ex -> new LlmProviderException(
                        "Gemini call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()),
                        ex.getStatusCode().is5xxServerError(), ex))
                .onErrorMap(ex -> !(ex instanceof LlmProviderException), ex ->
                        new LlmProviderException("Gemini call failed: " + ex.getMessage(), true, ex));
    }

    /**
     * Embedding models use a completely different Gemini endpoint/shape (embedContent, not
     * generateContent) -- the vector rides through the same one-string {@code LlmResponse.content}
     * field every other non-text result uses (TTS audio, generated images), as a JSON array of
     * doubles, so this reuses {@code /v1/chat}'s existing idempotency/job/wallet-guard machinery
     * instead of needing a whole separate endpoint.
     */
    @SuppressWarnings("unchecked")
    private Mono<LlmResponse> generateEmbedding(CanonicalRequest request) {
        String text = request.messages().stream()
                .filter(m -> "user".equalsIgnoreCase(m.role()))
                .map(ChatMessage::content)
                .collect(Collectors.joining("\n"));
        Map<String, Object> body = Map.of("content", Map.of("parts", List.of(Map.of("text", text))));
        String path = "/v1beta/models/%s:embedContent?key=%s".formatted(request.modelId(), apiKey);
        int timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : 15000;
        return webClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(response -> {
                    Map<String, Object> embedding = (Map<String, Object>) response.get("embedding");
                    List<Double> values = embedding == null ? List.of() : (List<Double>) embedding.get("values");
                    return new LlmResponse(values.toString(), text.length() / 4, 0, "STOP", List.of());
                })
                .onErrorMap(WebClientResponseException.class, ex -> new LlmProviderException(
                        "Gemini embedContent call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()),
                        ex.getStatusCode().is5xxServerError(), ex))
                .onErrorMap(ex -> !(ex instanceof LlmProviderException), ex ->
                        new LlmProviderException("Gemini embedContent call failed: " + ex.getMessage(), true, ex));
    }

    /** Parses a {@code data:<mimeType>;base64,<data>} URI into Gemini's {@code inlineData} shape. */
    private Map<String, Object> toInlineData(String dataUri) {
        int comma = dataUri.indexOf(',');
        String header = dataUri.substring(5, dataUri.indexOf(';'));
        return Map.of("mimeType", header, "data", dataUri.substring(comma + 1));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> toGeminiRequestBody(CanonicalRequest request) {
        String systemText = request.messages().stream()
                .filter(m -> "system".equalsIgnoreCase(m.role()))
                .map(ChatMessage::content)
                .collect(Collectors.joining("\n"));

        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            if ("system".equalsIgnoreCase(message.role())) {
                continue;
            }
            if ("tool".equalsIgnoreCase(message.role())) {
                // Caller's result for a previously-requested functionCall.
                contents.add(Map.of(
                        "role", "function",
                        "parts", List.of(Map.of("functionResponse", Map.of(
                                "name", message.name() == null ? "" : message.name(),
                                "response", Map.of("content", message.content())
                        )))
                ));
                continue;
            }
            String geminiRole = "assistant".equalsIgnoreCase(message.role()) ? "model" : "user";
            List<Map<String, Object>> parts = new ArrayList<>();
            parts.add(Map.of("text", message.content()));
            if (message.imageDataUris() != null) {
                for (String dataUri : message.imageDataUris()) {
                    parts.add(Map.of("inlineData", toInlineData(dataUri)));
                }
            }
            contents.add(Map.of("role", geminiRole, "parts", parts));
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        if (params.get("temperature") != null) {
            generationConfig.put("temperature", params.get("temperature"));
        }
        if (params.get("max_tokens") != null) {
            generationConfig.put("maxOutputTokens", params.get("max_tokens"));
        }
        // Forces the model to emit a JSON document at the API level (Gemini rejects/repairs
        // anything else) rather than relying on the prompt saying "return JSON only" -- callers
        // that parse the response as structured JSON (critic-service, shot-list generation, ...)
        // should always set this instead of trusting prompt wording alone.
        if ("json".equalsIgnoreCase(String.valueOf(params.get("response_format")))) {
            generationConfig.put("responseMimeType", "application/json");
        }
        // Image-typed models (gemini-2.5-flash-image) only actually return image bytes when the
        // request explicitly asks for the IMAGE modality -- mirrors creator-service's real,
        // already-working StoryboardImageGenerationService request shape.
        if ("image".equalsIgnoreCase(String.valueOf(params.get("response_format")))) {
            generationConfig.put("responseModalities", List.of("IMAGE"));
            // Without this, aspect ratio was only ever prose in the prompt text ("9:16
            // composition") -- Gemini has no obligation to honor that and confirmed live it
            // often didn't (square/landscape output on a shot configured for 9:16). Gemini
            // actually supports a structural ratio here: generationConfig.imageConfig.aspectRatio,
            // one of "1:1"/"2:3"/"3:2"/"3:4"/"4:3"/"4:5"/"5:4"/"9:16"/"16:9"/"21:9" -- defaults to
            // 1:1 if omitted, which is exactly the failure mode reported.
            if (params.get("aspect_ratio") != null) {
                generationConfig.put("imageConfig", Map.of("aspectRatio", params.get("aspect_ratio")));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", contents);
        if (!generationConfig.isEmpty()) {
            body.put("generationConfig", generationConfig);
        }
        if (!systemText.isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemText))));
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<Map<String, Object>> declarations = request.tools().stream()
                    .map(this::toFunctionDeclaration)
                    .toList();
            body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
        }
        return body;
    }

    private Map<String, Object> toFunctionDeclaration(ToolDefinition tool) {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("name", tool.name());
        if (tool.description() != null) {
            declaration.put("description", tool.description());
        }
        if (tool.parameters() != null) {
            declaration.put("parameters", tool.parameters());
        }
        return declaration;
    }

    @SuppressWarnings("unchecked")
    LlmResponse toLlmResponse(Map<String, Object> response) {
        if (response == null) {
            throw new LlmProviderException("Gemini returned an empty response", true);
        }
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new LlmProviderException("Gemini returned no candidates: " + response, false);
        }
        Map<String, Object> first = candidates.get(0);
        Map<String, Object> content = (Map<String, Object>) first.get("content");
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (content != null) {
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts != null) {
                for (Map<String, Object> part : parts) {
                    if (part.get("text") != null) {
                        text.append(String.valueOf(part.get("text")));
                    }
                    // Image-typed models (gemini-2.5-flash-image, ...) return the generated image
                    // inline here instead of/alongside text -- encoded as a data URI, same
                    // one-string-result convention ElevenLabsProvider uses for TTS bytes.
                    Map<String, Object> inlineData = (Map<String, Object>) part.get("inlineData");
                    if (inlineData != null && inlineData.get("data") != null) {
                        String mimeType = String.valueOf(inlineData.getOrDefault("mimeType", "image/png"));
                        text.append("data:").append(mimeType).append(";base64,").append(inlineData.get("data"));
                    }
                    Map<String, Object> functionCall = (Map<String, Object>) part.get("functionCall");
                    if (functionCall != null) {
                        toolCalls.add(new ToolCall(
                                UUID.randomUUID().toString(),
                                String.valueOf(functionCall.get("name")),
                                (Map<String, Object>) functionCall.getOrDefault("args", Map.of())
                        ));
                    }
                }
            }
        }
        String finishReason = String.valueOf(first.getOrDefault("finishReason", ""));

        Map<String, Object> usage = (Map<String, Object>) response.get("usageMetadata");
        int inputTokens = usage != null ? asInt(usage.get("promptTokenCount")) : 0;
        int outputTokens = usage != null ? asInt(usage.get("candidatesTokenCount")) : 0;

        return new LlmResponse(text.toString(), inputTokens, outputTokens, finishReason, toolCalls);
    }

    private int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
