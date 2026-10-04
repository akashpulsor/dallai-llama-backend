package com.dalai.llama.videogen.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** What a video model can be asked for. See V39 for where each row's numbers came from. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "video_model_generation_capability")
public class VideoModelGenerationCapability {

    @Id
    @Column(name = "model_id", length = 128)
    private String modelId;

    @Column(name = "min_duration_seconds", nullable = false)
    private Integer minDurationSeconds;

    @Column(name = "max_duration_seconds", nullable = false)
    private Integer maxDurationSeconds;

    @Column(name = "source_note", columnDefinition = "TEXT")
    private String sourceNote;
}
