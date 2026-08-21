package com.dalai.llama.creativeplanning.domain.entity;

import com.dalai.llama.creativeplanning.domain.MessageRole;
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

/** "Talk to your marketing plan" -- Q&A/discussion grounded in the full plan content. Requesting
 * an actual change doesn't edit the plan inline; it flags the plan for {@code
 * MarketingPlanRevisionService} to explicitly regenerate, same discipline as creator-service's
 * real chat-scene-sets-NEEDS_REGENERATION precedent this session already validated. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "marketing_plan_message")
public class MarketingPlanMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private MessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
