package com.dalai.llama.chat.service.client;

import com.dalai.llama.chat.service.ChatException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class TrendIntelligenceServiceClient {

    public record GenerateTrendReportRequest(String topic, String industry, String targetAudience) {
    }

    /** Mirrors trend-intelligence-service's own {@code TrendPredictionItemView} -- only the
     * fields chat-service actually reads for its summary message. */
    public record TrendPredictionItemView(String title, String summary) {
    }

    public record TrendReportView(UUID id, String topic, List<TrendPredictionItemView> predictions) {
    }

    private final WebClient webClient;
    private final int timeoutMs;

    public TrendIntelligenceServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${chat.trend-intelligence.base-url}") String baseUrl,
            @Value("${chat.trend-intelligence.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public TrendReportView generate(String tenantId, GenerateTrendReportRequest request) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/trend-reports", tenantId)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(TrendReportView.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw ChatException.upstream(
                    "trend-intelligence-service trend-reports failed status=%s body=%s"
                            .formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
