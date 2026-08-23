package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.ChangeRequestStatus;
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

/** A client's chat-suggested change, logged (never auto-applied) by chat-service's {@code
 * SuggestPreProductionChangeActionExecutor}. {@code targetType} is a {@link
 * com.dalai.llama.preprod.domain.entity.SuggestionTargetType#getCode()} value, validated at
 * creation time -- never a hardcoded enum, see that class's javadoc. The creator applies or
 * dismisses it from their own (authenticated) project page. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "change_request")
public class ChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    /** Required when the target type's requiresTargetRef is true (e.g. SHOT_IMAGE's
     * "<shotNumber>:<imageKind>"), null otherwise. */
    @Column(name = "target_ref", length = 200)
    private String targetRef;

    @Column(name = "note", nullable = false, columnDefinition = "text")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChangeRequestStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
