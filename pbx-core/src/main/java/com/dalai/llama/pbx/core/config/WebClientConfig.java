package com.dalai.llama.pbx.core.config;


import com.dalai.llama.pbx.core.client.AiServiceClient;
import com.dalai.llama.pbx.core.client.ProductServiceClient;
import com.dalai.llama.pbx.core.client.TenantServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;


/**
 * Configuration for reactive WebClient instances communicating with internal microservices.
 */
@Configuration
public class WebClientConfig {

    @Value("${dalaillama.tenant-service.url:http://tenant-service}")
    private String tenantServiceBaseUrl;

    @Value("${dalaillama.product-service.url:http://product-service}")
    private String productServiceBaseUrl;

    @Value("${dalaillama.service.ai-service.url:http://ai-service:8600}")
    private String aiServiceBaseUrl;

    @Bean
    public TenantServiceClient tenantClient(WebClient.Builder webClientBuilder) {
        return new TenantServiceClient(webClientBuilder
                .baseUrl(tenantServiceBaseUrl)
                .build());
    }

    @Bean
    public ProductServiceClient productClient(WebClient.Builder webClientBuilder) {
        return new ProductServiceClient(webClientBuilder
                .baseUrl(productServiceBaseUrl)
                .build());
    }

    @Bean
    public AiServiceClient aiServiceClient(WebClient.Builder webClientBuilder) {
        return new AiServiceClient(webClientBuilder
                .baseUrl(aiServiceBaseUrl)
                .build());
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }
}
