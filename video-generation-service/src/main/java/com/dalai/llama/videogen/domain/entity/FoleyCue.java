package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.FoleyCueType;
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

import java.util.UUID;

/**
 * Cue sheet only -- no audio is generated here (design doc §4.2/§6). Post-production reads this
 * table to know what foley/BGM to generate and where, without re-deriving it from ShotContext.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "foley_cue")
public class FoleyCue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cue_id")
    private Long cueId;

    @Column(name = "prompt_id", nullable = false)
    private UUID promptId;

    @Column(name = "timestamp_ms", nullable = false)
    private Integer timestampMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "cue_type", nullable = false, length = 32)
    private FoleyCueType cueType;

    @Column(nullable = false)
    private String description;
}
