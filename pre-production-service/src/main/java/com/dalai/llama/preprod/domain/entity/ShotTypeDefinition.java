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

/** Master/reference data for {@link com.dalai.llama.preprod.domain.ShotType} -- the enum itself
 * stays the Java dispatch key {@code ShotContextAssemblyStrategyResolver} routes on (a DB table
 * can't drive that), but everything a UI needs to SHOW about a shot type (friendly label,
 * description, whether it needs video generation vs. motion-graphics planning) lives here instead
 * of being duplicated as frontend string literals. {@code code} must match a real {@link
 * com.dalai.llama.preprod.domain.ShotType} constant name. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_type_definition")
public class ShotTypeDefinition {

    @Id
    @Column(name = "code", length = 32)
    private String code;

    @Column(name = "label", nullable = false, length = 80)
    private String label;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    /** True for every type today except MOTION_GRAPHIC -- dispatches through video-generation-
     * service. */
    @Column(name = "requires_video_generation", nullable = false)
    private Boolean requiresVideoGeneration;

    /** True only for MOTION_GRAPHIC -- planned via {@code MotionGraphicPlanService} instead of
     * dispatched to video-generation-service (this codebase has no motion-graphics rendering
     * engine; planning what the graphic should contain is what's actually built). */
    @Column(name = "requires_motion_graphics", nullable = false)
    private Boolean requiresMotionGraphics;

    @Column(name = "active", nullable = false)
    private Boolean active;
}
