package com.dalai.llama.pbx.core.config;


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

    @Bean
    public WebClient tenantServiceClient(WebClient.Builder webClientBuilder) {
        return webClientBuilder
                .baseUrl(tenantServiceBaseUrl)
                .build();
    }

    @Bean
    public WebClient productServiceClient(WebClient.Builder webClientBuilder) {
        return webClientBuilder
                .baseUrl(productServiceBaseUrl)
                .build();
    }
}
