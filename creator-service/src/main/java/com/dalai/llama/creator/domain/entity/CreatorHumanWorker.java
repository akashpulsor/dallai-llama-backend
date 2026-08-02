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

import java.time.OffsetDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_human_workers")
public class CreatorHumanWorker {

    @Id
    @Column(name = "user_id", nullable = false, length = 160)
    private String userId;

    @Column(nullable = false, length = 40)
    private String role;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(length = 240)
    private String email;

    @Column(nullable = false)
    private boolean online;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "active_assignment_count", nullable = false)
    private int activeAssignmentCount;

    @Column(name = "total_assignment_count", nullable = false)
    private long totalAssignmentCount;

    @Column(name = "last_assigned_at")
    private OffsetDateTime lastAssignedAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (role == null || role.isBlank()) {
            role = "COPYWRITER";
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = userId;
        }
        active = true;
        if (lastSeenAt == null) {
            lastSeenAt = now;
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
