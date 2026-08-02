package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_human_work_orders")
public class CreatorHumanWorkOrder {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "locked_idea_id")
    private UUID lockedIdeaId;

    @Column(name = "story_idea_id")
    private UUID storyIdeaId;

    @Column(name = "script_id")
    private UUID scriptId;

    @Column(name = "video_run_id")
    private UUID videoRunId;

    @Column(name = "work_type", nullable = false, length = 64)
    private String workType;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "requester_notes", columnDefinition = "text")
    private String requesterNotes;

    @Column(name = "reviewer_notes", columnDefinition = "text")
    private String reviewerNotes;

    @Column(name = "assigned_to", length = 160)
    private String assignedTo;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> sourcePayload = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "delivery_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> deliveryPayload = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> conversation = new ArrayList<>();

    @Column(name = "price_amount", nullable = false, precision = 12, scale = 4)
    private BigDecimal priceAmount;

    @Column(name = "price_currency", nullable = false, length = 3)
    private String priceCurrency;

    @Column(name = "billing_status", nullable = false, length = 40)
    private String billingStatus;

    @Column(name = "billing_reference", length = 160)
    private String billingReference;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "rejected_at")
    private OffsetDateTime rejectedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (status == null || status.isBlank()) {
            status = "SUBMITTED";
        }
        if (sourcePayload == null) {
            sourcePayload = new LinkedHashMap<>();
        }
        if (deliveryPayload == null) {
            deliveryPayload = new LinkedHashMap<>();
        }
        if (conversation == null) {
            conversation = new ArrayList<>();
        }
        if (priceAmount == null) {
            priceAmount = BigDecimal.ZERO;
        }
        if (priceCurrency == null || priceCurrency.isBlank()) {
            priceCurrency = "INR";
        }
        if (billingStatus == null || billingStatus.isBlank()) {
            billingStatus = priceAmount.signum() > 0 ? "PENDING_APPROVAL" : "NOT_REQUIRED";
        }
        if (submittedAt == null) {
            submittedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
