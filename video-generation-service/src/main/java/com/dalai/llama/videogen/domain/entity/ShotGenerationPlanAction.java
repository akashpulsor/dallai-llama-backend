package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.ShotActionKind;
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

import java.math.BigDecimal;
import java.util.UUID;

/** One action the approved shot plan requires, numbered once and checked against at every later step. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "shot_generation_plan_action")
public class ShotGenerationPlanAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    @Column(name = "action_id", nullable = false, length = 16)
    private String actionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private ShotActionKind kind;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "fixed_seconds", precision = 6, scale = 3)
    private BigDecimal fixedSeconds;

    @Column(name = "depends_on_action_id", length = 16)
    private String dependsOnActionId;
}
