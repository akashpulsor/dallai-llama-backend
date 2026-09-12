package com.dalai.llama.llmgateway.domain.entity;

import com.dalai.llama.llmgateway.domain.LanguageDeliveryMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "provider_language_mapping")
public class ProviderLanguageMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mapping_id")
    private Long mappingId;

    @Column(name = "provider_id", nullable = false, length = 64)
    private String providerId;

    /** Null means the provider-wide default; an exact model id overrides it. */
    @Column(name = "model_id", length = 128)
    private String modelId;

    @Column(name = "language_code", nullable = false, length = 16)
    private String languageCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 16)
    private LanguageDeliveryMode deliveryMode;

    @Column(name = "provider_parameter_name", length = 64)
    private String providerParameterName;

    @Column(name = "provider_language_code", length = 64)
    private String providerLanguageCode;

    @Column(nullable = false)
    private Boolean active;

    public boolean appliesTo(String routedModelId) {
        return modelId == null || modelId.equals(routedModelId);
    }

    public boolean isModelSpecific() {
        return modelId != null;
    }
}
