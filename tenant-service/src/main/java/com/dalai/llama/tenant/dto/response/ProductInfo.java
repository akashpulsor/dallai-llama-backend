package com.dalai.llama.tenant.dto.response;

import com.dalai.llama.tenant.domain.entity.enums.ProductType;
import lombok.Builder;

import java.util.UUID;

/**
 * Product information
 */
@Builder
public record ProductInfo(
        UUID id,
        String code,
        String name,
        ProductType type,
        String description
) {}
