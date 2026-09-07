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
import java.time.OffsetDateTime;
import java.util.UUID;

/** Snapshot of one {@code DialogueBeat} from the request's {@code ShotContext}, persisted at
 * generate() time so approve()'s later dispatch (which only has {@code jobId}, per {@link
 * VideoGenJob}'s existing "capture now, read at approve" convention) can still act on it. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "video_gen_job_dialogue_beat")
public class VideoGenJobDialogueBeat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(name = "start_seconds", nullable = false)
    private BigDecimal startSeconds;

    @Column(name = "duration_seconds", nullable = false)
    private BigDecimal durationSeconds;

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "character_key", length = 160)
    private String characterKey;

    @Column(name = "voice_reference_url", columnDefinition = "text")
    private String voiceReferenceUrl;

    @Column(name = "builtin_voice_id")
    private String builtinVoiceId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
