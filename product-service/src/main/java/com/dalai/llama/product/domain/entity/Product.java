package com.dalai.llama.product.domain.entity;


import com.dalai.llama.product.domain.entity.enums.ProductType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "products")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private ProductType type;

    private boolean active = true;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> features;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;
}
