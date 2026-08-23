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

/** Master/reference data for {@link com.dalai.llama.preprod.domain.AspectRatio} -- that enum's
 * names are a wire contract with video-generation-service's own AspectRatio enum (must match
 * exactly, see its javadoc) and stays as-is; this table is purely display data (friendly label,
 * horizontal/vertical/square orientation) for the project-settings picker. {@code code} must
 * match a real {@link com.dalai.llama.preprod.domain.AspectRatio} constant name. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "aspect_ratio_option")
public class AspectRatioOption {

    @Id
    @Column(name = "code", length = 32)
    private String code;

    @Column(name = "label", nullable = false, length = 40)
    private String label;

    @Column(name = "orientation", nullable = false, length = 16)
    private String orientation;

    @Column(name = "active", nullable = false)
    private Boolean active;
}
