package com.dalai.llama.product.dto.response;


import com.dalai.llama.product.domain.entity.enums.ProductType;
import lombok.*;

import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {

    private UUID id;
    private String code;
    private String name;
    private String description;
    private ProductType type;
    private boolean active;
    private Map<String, Object> features;
}
