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

    @Value("${services.tenant.base-url:http://tenant-service}")
    private String tenantServiceBaseUrl;

    @Value("${services.product.base-url:http://product-service}")
    private String productServiceBaseUrl;

    /**
     * WebClient for the Tenant Service.
     */
    @Bean(name = "tenantWebClient")
    public WebClient tenantWebClient(WebClient.Builder webClientBuilder) {
        return webClientBuilder
                .baseUrl(tenantServiceBaseUrl)
                .build();
    }

    /**
     * WebClient for the Product Service.
     */
    @Bean(name = "productWebClient")
    public WebClient productWebClient(WebClient.Builder webClientBuilder) {
        return webClientBuilder
                .baseUrl(productServiceBaseUrl)
                .build();
    }
}
