package com.dalai.llama.product.domain.entity;

import com.dalai.llama.product.domain.entity.enums.AiProviderType;
import com.dalai.llama.product.domain.entity.enums.AiStackType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "ai_providers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_provider_model",
                columnNames = {"provider_type", "provider_name", "model_name"}
        ),
        indexes = {
                @Index(name = "idx_ai_provider_type", columnList = "provider_type"),
                @Index(name = "idx_ai_provider_tier", columnList = "quality_tier"),
                @Index(name = "idx_ai_provider_active", columnList = "is_active")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiProvider {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 10)
    private AiProviderType providerType;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "cost_per_min", nullable = false, precision = 8, scale = 4)
    private BigDecimal costPerMin;

    @Column(name = "cost_per_1k_tokens", precision = 8, scale = 4)
    private BigDecimal costPer1kTokens;

    @Column(length = 3)
    @Builder.Default
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_tier", length = 20)
    private AiStackType qualityTier;

    @Column(name = "is_dalai_llama")
    @Builder.Default
    private boolean dalaiLlama = false;

    @Column(name = "is_active")
    @Builder.Default
    private boolean active = true;

    @Column(name = "icon_url", length = 500)
    private String iconUrl;

    @Column(name = "icon_svg", columnDefinition = "TEXT")
    private String iconSvg;

    @Column(name = "brand_color", length = 7)
    private String brandColor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "languages_supported")
    private List<String> languagesSupported;

    @Column(name = "supports_streaming")
    @Builder.Default
    private boolean supportsStreaming = true;

    @Column(name = "supports_realtime")
    @Builder.Default
    private boolean supportsRealtime = false;

    @Column(name = "avg_latency_ms")
    private Integer avgLatencyMs;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
