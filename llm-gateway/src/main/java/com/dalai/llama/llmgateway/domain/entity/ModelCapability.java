package com.dalai.llama.llmgateway.domain.entity;

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

/** How strong a model is at a named generation capability (CAMERA_MOTION, OBJECT_CONSISTENCY,
 * TEXT_RENDERING, MACRO_TEXTURE, COMPLEX_PHYSICS, LONG_CONTINUOUS_SHOTS, ...) -- feeds
 * critic-service's Level 4 generation-feasibility check. A real table, not another key inside
 * {@link ModelMaster#getCapabilities()}'s jsonb blob. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "model_capability")
public class ModelCapability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "model_id", nullable = false, length = 128)
    private String modelId;

    @Column(name = "capability_key", nullable = false, length = 64)
    private String capabilityKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "strength", nullable = false, length = 16)
    private CapabilityStrength strength;
}
