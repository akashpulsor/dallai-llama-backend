package com.dalai.llama.llmgateway.service.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reads a cast sample through pre-production-service's scoped internal endpoint. The gateway
 * never receives a public MinIO URL and therefore cannot be redirected to arbitrary hosts by a
 * model parameter. Pre-production remains the owner of cast authorization and object storage.
 */
@Component
public class PreProductionVoiceReferenceClient {

    private static final int MAX_REFERENCE_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;

    public PreProductionVoiceReferenceClient(
            @Value("${llm-gateway.pre-production-service-url}") String preProductionServiceUrl
    ) {
        this.webClient = WebClient.builder()
                .baseUrl(preProductionServiceUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_REFERENCE_BYTES))
                        .build())
                .build();
    }

    public Mono<VoiceReference> fetch(ProviderRequestContext context, UUID castProfileId) {
        if (context == null || context.tenantId() == null || context.tenantId().isBlank() || context.projectId() == null) {
            return Mono.error(new LlmProviderException(
                    "A tenant and project context are required to resolve a cast voice reference", false));
        }
        return webClient.get()
                .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/cast-profiles/{castProfileId}/voice-reference",
                        context.tenantId(), context.projectId(), castProfileId)
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
                        return response.createException().flatMap(Mono::error);
                    }
                    MediaType contentType = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
                    String filename = filename(response.headers(), castProfileId);
                    return response.bodyToMono(byte[].class)
                            .map(bytes -> new VoiceReference(bytes, filename, contentType));
                })
                .onErrorMap(DataBufferLimitException.class, ex -> new LlmProviderException(
                        "Cast voice reference exceeds the 16 MiB cloning limit", false, ex));
    }

    private String filename(HttpHeaders headers, UUID castProfileId) {
        String header = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        if (header != null && !header.isBlank()) {
            try {
                String filename = ContentDisposition.parse(header).getFilename();
                if (filename != null && !filename.isBlank()) {
                    return filename;
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to a safe filename; the media bytes are still valid.
            }
        }
        return "cast-voice-" + castProfileId + ".mp3";
    }

    public record VoiceReference(byte[] bytes, String filename, MediaType contentType) {
    }
}
