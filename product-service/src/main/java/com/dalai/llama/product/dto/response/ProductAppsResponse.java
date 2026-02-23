package com.dalai.llama.product.dto.response;

import lombok.Builder;

import java.util.List;

/**
 * Wrapper for product apps list.
 */
@Builder
public record ProductAppsResponse(
        String productCode,
        String productName,
        List<ProductAppResponse> apps
) {}