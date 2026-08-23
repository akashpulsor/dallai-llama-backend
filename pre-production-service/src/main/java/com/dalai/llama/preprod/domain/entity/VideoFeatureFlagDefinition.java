package com.dalai.llama.preprod.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Master/reference data for the two feature flags video-generation-service's own {@code
 * FeatureFlags} record supports (dialogue, captions) -- that record stays the fixed wire shape
 * (see pre-production-service's local {@code FeatureFlags} copy), this table is what lets the
 * frontend render toggles with real labels/descriptions instead of two hardcoded checkboxes.
 * {@code flagKey} must match a real field name on that record. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "video_feature_flag_definition")
public class VideoFeatureFlagDefinition {

    @Id
    @Column(name = "flag_key", length = 32)
    private String flagKey;

    @Column(name = "label", nullable = false, length = 80)
    private String label;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "default_enabled", nullable = false)
    private Boolean defaultEnabled;

    @Column(name = "active", nullable = false)
    private Boolean active;
}
