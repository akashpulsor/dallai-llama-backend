package com.dalai.llama.creator.ai;

import com.dalai.llama.creator.config.CreatorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiRateLimitGuardTest {

    @Test
    void monthlySpendCapFailsImmediatelyWithActionableMessage() {
        CreatorProperties properties = new CreatorProperties();
        properties.getAi().setGeminiMaxAttempts(3);
        properties.getAi().setGeminiRetryBackoffMs(60_000);
        properties.getAi().setGeminiRateLimitCooldownMs(60_000);
        properties.getAi().setGeminiRequestMinIntervalMs(0);

        String responseBody = """
                {
                  "error": {
                    "code": 429,
                    "message": "Your project has exceeded its monthly spending cap. Please go to AI Studio to manage your project spend cap.",
                    "status": "RESOURCE_EXHAUSTED"
                  }
                }
                """;
        WebClientResponseException capException = WebClientResponseException.create(
                429,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                responseBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
        AtomicInteger attempts = new AtomicInteger();
        GeminiRateLimitGuard guard = new GeminiRateLimitGuard(properties);

        assertThatThrownBy(() -> guard.execute("CLIENT_REVIEW_RAG_CHAT", "gemini-2.5-flash", () -> {
            attempts.incrementAndGet();
            throw capException;
        }))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("monthly project spending cap")
                .hasMessageContaining("https://ai.studio/spend")
                .hasMessageContaining("10 minutes");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void retriesStoryboardImageUsingGoogleRetryInfoInsteadOfConfiguredMinuteCooldown() {
        CreatorProperties properties = new CreatorProperties();
        properties.getAi().setGeminiMaxAttempts(2);
        properties.getAi().setGeminiRetryBackoffMs(0);
        properties.getAi().setGeminiRetryMaxDelayMs(30_000);
        properties.getAi().setGeminiRateLimitCooldownMs(60_000);
        properties.getAi().setGeminiRateLimitMaxLocalWaitMs(5_000);
        properties.getAi().setGeminiRequestMinIntervalMs(0);

        String responseBody = """
                {
                  "error": {
                    "code": 429,
                    "message": "Quota exceeded. Please retry in 0.001s.",
                    "details": [
                      {
                        "@type": "type.googleapis.com/google.rpc.RetryInfo",
                        "retryDelay": "0.001s"
                      }
                    ]
                  }
                }
                """;
        WebClientResponseException quotaException = WebClientResponseException.create(
                429,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                responseBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
        AtomicInteger attempts = new AtomicInteger();
        GeminiRateLimitGuard guard = new GeminiRateLimitGuard(properties);

        String result = guard.execute("STORYBOARD_IMAGE_GENERATE", "gemini-image", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw quotaException;
            }
            return "generated";
        });

        assertThat(result).isEqualTo("generated");
        assertThat(attempts).hasValue(2);
    }
}
