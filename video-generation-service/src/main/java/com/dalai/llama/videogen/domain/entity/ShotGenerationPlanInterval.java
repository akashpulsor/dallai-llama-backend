package com.dalai.llama.videogen.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One interval of the second-by-second source timeline. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_generation_plan_interval")
public class ShotGenerationPlanInterval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    @Column(name = "start_seconds", nullable = false, precision = 6, scale = 3)
    private BigDecimal startSeconds;

    @Column(name = "end_seconds", nullable = false, precision = 6, scale = 3)
    private BigDecimal endSeconds;

    @Column(name = "action_id", nullable = false, length = 64)
    private String actionId;

    @Column(name = "action", nullable = false, columnDefinition = "TEXT")
    private String action;

    @Column(name = "subject_state", columnDefinition = "TEXT")
    private String subjectState;

    @Column(name = "camera_behavior", columnDefinition = "TEXT")
    private String cameraBehavior;

    @Column(name = "hold_required", nullable = false)
    private Boolean holdRequired;
}
