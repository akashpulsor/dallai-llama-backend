package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.ContinuityLockCategory;
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

/** One deduped locked value within a {@link ContinuityBible}, e.g. category=WARDROBE_APPEARANCE,
 * value="Maya: red kurta, gold hoop earrings". Capped at 8 per category when recomputed, same
 * cap creator-service's real bible used. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "continuity_lock")
public class ContinuityLock {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "continuity_bible_id", nullable = false)
    private UUID continuityBibleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 24)
    private ContinuityLockCategory category;

    @Column(name = "value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
