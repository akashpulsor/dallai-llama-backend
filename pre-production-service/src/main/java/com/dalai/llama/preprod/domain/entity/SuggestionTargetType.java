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

/** Master/reference data: what a client's chat suggestion can target, and what {@link
 * com.dalai.llama.preprod.domain.entity.ChangeRequest#getTargetType()} is validated against. Real
 * table, not a hardcoded enum or a string list duplicated in chat-service's code -- chat-service
 * reads this catalog (see {@code GET /api/v1/internal/suggestion-target-types}) instead of
 * carrying its own copy of "which target types exist" that would silently drift from this
 * service's actual regeneration capabilities. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "suggestion_target_type")
public class SuggestionTargetType {

    @Id
    @Column(name = "code", length = 32)
    private String code;

    @Column(name = "label", nullable = false, length = 80)
    private String label;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    /** True when a suggestion against this target type must also carry {@code targetRef} (e.g.
     * SHOT_IMAGE needs "which shot, which image kind"); false for a whole-artifact target like
     * SCRIPT that has exactly one instance per project. */
    @Column(name = "requires_target_ref", nullable = false)
    private Boolean requiresTargetRef;

    /** Shown to the model so it knows what shape to put in targetRef when one is required, e.g.
     * {@code "<shotNumber>:<imageKind>"}. Null when requiresTargetRef is false. */
    @Column(name = "target_ref_hint", length = 200)
    private String targetRefHint;

    @Column(name = "active", nullable = false)
    private Boolean active;
}
