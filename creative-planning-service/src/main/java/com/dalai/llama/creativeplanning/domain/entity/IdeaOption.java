package com.dalai.llama.creativeplanning.domain.entity;

import com.dalai.llama.creativeplanning.domain.IdeaOptionSource;
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

/** One persisted idea candidate for a {@code ProjectRequirement} -- generate() saves every
 * option it returns (source=GENERATED, parentId=null); saving a creator's edit of one saves a
 * new row instead of mutating the original (source=EDITED, parentId=the option it was edited
 * from). Nothing here is ever deleted, so the full lineage of an idea survives a page refresh. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "idea_option")
public class IdeaOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_requirement_id", nullable = false)
    private UUID projectRequirementId;

    @Column(name = "title", nullable = false, length = 240)
    private String title;

    @Column(name = "concept", columnDefinition = "text")
    private String concept;

    @Column(name = "target_audience", columnDefinition = "text")
    private String targetAudience;

    @Column(name = "campaign_angle", columnDefinition = "text")
    private String campaignAngle;

    @Column(name = "key_message", columnDefinition = "text")
    private String keyMessage;

    @Column(name = "tone", length = 240)
    private String tone;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private IdeaOptionSource source;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
