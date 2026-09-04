package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.GenerationSource;
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

import java.time.OffsetDateTime;
import java.util.UUID;

/** One snapshot of a project's script content -- see {@code V41__script_version.sql}'s javadoc
 * for why this is a separate, additive table rather than turning {@code Script} itself into a
 * multi-row-per-project table the way {@link Screenplay} is: {@code Script} stays the single
 * "current draft" row every other service in this codebase already joins against by id, and
 * {@link com.dalai.llama.preprod.service.ScriptGenerationService} keeps it in sync with whichever
 * version is current. This table exists purely for version-history/rollback UI to read. {@code
 * scriptCharacterId} rows are deliberately never duplicated here -- characters aren't versioned,
 * same as how screenplay's characters live on {@code Script} untouched by screenplay versioning. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "script_version")
public class ScriptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "script_id", nullable = false)
    private UUID scriptId;

    /** 1-based, increasing per project -- generate()/saveEdit() always insert a new row with the
     * next number rather than reusing the existing one. */
    @Column(name = "version", nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private GenerationSource source;

    /** Set only for EDITED versions -- the version this one was manually edited from. */
    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "script_text", nullable = false, columnDefinition = "text")
    private String scriptText;

    @Column(name = "pacing_style", length = 240)
    private String pacingStyle;

    @Column(name = "emotional_arc", columnDefinition = "text")
    private String emotionalArc;

    @Column(name = "hook_strategy", columnDefinition = "text")
    private String hookStrategy;

    @Column(name = "no_humans", nullable = false)
    @Builder.Default
    private Boolean noHumans = false;

    @Column(name = "logline", columnDefinition = "text")
    private String logline;

    @Column(name = "central_conflict", columnDefinition = "text")
    private String centralConflict;

    @Column(name = "ending_payoff", columnDefinition = "text")
    private String endingPayoff;

    @Column(name = "setting", columnDefinition = "text")
    private String setting;

    @Column(name = "hook", columnDefinition = "text")
    private String hook;

    @Column(name = "storytelling_type", length = 80)
    private String storytellingType;

    /** The critic's actual feedback that forced this rewrite -- only ever set when {@code source}
     * is CRITIC, so version-history UI can show why, not just that a critic pass happened. */
    @Column(name = "critique_notes", columnDefinition = "text")
    private String critiqueNotes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
