package com.dalai.llama.tenant.service.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client for Product Service - fetches product and app configurations
 */
@Slf4j
@Component
public class ProductServiceClient {

    private final WebClient.Builder webClientBuilder;
    private final String productServiceUrl;

    public ProductServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${services.product.url:http://product-service:8080}") String productServiceUrl) {
        this.webClientBuilder = webClientBuilder;
        this.productServiceUrl = productServiceUrl;
    }

    private WebClient client() {
        return webClientBuilder.baseUrl(productServiceUrl).build();
    }

    /**
     * Check if tenant has purchased a DID
     */
    public boolean hasPurchasedDid(UUID tenantId) {
        try {
            Boolean result = client().get()
                    .uri("/api/v1/internal/tenants/{id}/dids", tenantId)
                    .retrieve()
                    .bodyToMono(Boolean.class)
                    .block();
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to check DID purchase for tenant {}: {}", tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Assign default plan to tenant
     */
    public boolean assignDefaultPlan(UUID tenantId, String productCode) {
        try {
            Boolean result = client().post()
                    .uri("/api/v1/internal/tenants/{id}/assign-default-plan?productCode={code}", tenantId, productCode)
                    .retrieve()
                    .bodyToMono(Boolean.class)
                    .block();
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to assign default plan for tenant {}: {}", tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Get product by code
     */
    public ProductDto getProductByCode(String productCode) {
        log.debug("Fetching product: {}", productCode);
        return client().get()
                .uri("/api/v1/products/{code}", productCode)
                .retrieve()
                .bodyToMono(ProductDto.class)
                .block();
    }

    /**
     * Get apps for a product (called during provisioning)
     */
    public List<ProductAppDto> getProductApps(String productCode) {
        log.debug("Fetching apps for product: {}", productCode);
        return client().get()
                .uri("/api/v1/products/{code}/apps", productCode)
                .retrieve()
                .bodyToFlux(ProductAppDto.class)
                .collectList()
                .block();
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    @Data
    public static class ProductDto {
        private UUID id;
        private String code;
        private String name;
        private String description;
        private String type;  // ProductType enum as string
        private boolean active;
        private Map<String, Object> features;
    }

    @Data
    public static class ProductAppDto {
        private UUID id;
        private String appType;
        private String subdomain;
        private String displayName;
        private String keycloakClientSuffix;
        private String frontendImage;
        private Integer frontendPort;
        private String requiredRoles;
        private String icon;
        private Integer displayOrder;
    }
}
