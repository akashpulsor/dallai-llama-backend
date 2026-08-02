package com.dalai.llama.creator.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FalProviderBillingServiceTest {

    @Test
    void replacesConfiguredEstimateWithPerRequestFalBillingEvent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/billing-events", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            assertThat(query).contains("request_id=fal-request-1");
            byte[] body = """
                    {
                      "billing_events": [{
                        "request_id": "fal-request-1",
                        "endpoint_id": "fal-ai/heygen/avatar4/image-to-video",
                        "output_units": 6,
                        "unit_price": 0.10,
                        "cost_subtotal": 0.60,
                        "cost_discount": 0.06,
                        "cost_total": 0.54,
                        "cost_estimate_nano_usd": 540000000
                      }],
                      "has_more": false
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        System.setProperty("fal.platform-api-base-url", "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty("fal.admin-key", "test-admin-key");

        try {
            FalProviderBillingService service = new FalProviderBillingService(WebClient.builder());
            Map<String, Object> resolved = service.resolve(
                    "fal.ai",
                    "fal-ai/heygen/avatar4/image-to-video",
                    Map.of(
                            "modelApiInteracted", true,
                            "falRequestId", "fal-request-1",
                            "actualTotalCost", new BigDecimal("0.60"),
                            "customerTotalCost", new BigDecimal("0.72"),
                            "usage", Map.of("durationSeconds", 6)
                    )
            );

            assertThat(resolved.get("actualTotalCost")).isEqualTo(new BigDecimal("0.540000"));
            assertThat(resolved.get("pricingSource")).isEqualTo("FAL_BILLING_EVENTS_API");
            assertThat(resolved.get("estimated")).isEqualTo(false);
            assertThat(resolved).doesNotContainKey("customerTotalCost");
            assertThat((java.util.List<?>) resolved.get("providerBillingEvents")).hasSize(1);
        } finally {
            System.clearProperty("fal.platform-api-base-url");
            System.clearProperty("fal.admin-key");
            server.stop(0);
        }
    }
}
