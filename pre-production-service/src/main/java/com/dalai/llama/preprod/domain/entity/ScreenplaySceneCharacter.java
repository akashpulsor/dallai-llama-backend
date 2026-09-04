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

/** Which {@link ScriptCharacter}(s) actually appear in a given {@link ScreenplayScene} -- a real
 * relational join, not the free-text {@code characterFocus} column (kept for display fallback on
 * older/edited scenes). A scene with zero rows here is a pure motion-graphic/B-roll beat with no
 * character in it, not an oversight -- screenplay generation is expected to leave it empty on
 * purpose for those scenes rather than force a character reference that doesn't apply. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "screenplay_scene_character")
public class ScreenplaySceneCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "screenplay_scene_id", nullable = false)
    private UUID screenplaySceneId;

    @Column(name = "script_character_id", nullable = false)
    private UUID scriptCharacterId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
