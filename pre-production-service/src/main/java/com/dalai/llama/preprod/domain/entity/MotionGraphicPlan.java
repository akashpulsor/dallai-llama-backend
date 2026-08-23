package com.dalai.llama.preprod.domain.entity;

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

import java.time.OffsetDateTime;
import java.util.UUID;

/** What a MOTION_GRAPHIC shot should contain -- concept, on-screen text, visual style, animation
 * notes. Feeds {@code MotionGraphicShotContextAssemblyStrategy}, which turns this into the
 * dispatch prompt sent to video-generation-service (this IS rendered, not a planning-only
 * artifact -- see that strategy's javadoc). One plan per shot; regenerating overwrites, same
 * "no version history yet" convention ShotImage started with. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "motion_graphic_plan")
public class MotionGraphicPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shot_id", nullable = false, unique = true)
    private UUID shotId;

    @Column(name = "concept", columnDefinition = "text")
    private String concept;

    @Column(name = "on_screen_text", columnDefinition = "text")
    private String onScreenText;

    @Column(name = "visual_style", columnDefinition = "text")
    private String visualStyle;

    @Column(name = "animation_notes", columnDefinition = "text")
    private String animationNotes;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
