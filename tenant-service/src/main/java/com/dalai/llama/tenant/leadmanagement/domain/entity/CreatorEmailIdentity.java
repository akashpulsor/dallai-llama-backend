package com.dalai.llama.tenant.leadmanagement.domain.entity;

import com.dalai.llama.tenant.leadmanagement.domain.CreatorEmailIdentityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.UUID;

/** Application-managed virtual email identity for a creator (one row per tenant). See the
 * V24 migration for the shape and the intent; see
 * {@link com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService} for how it
 * is minted from {@code subscription.activated}. Not a real mailbox: the domain serves a
 * Cloudflare catch-all router that forwards inbound mail to the backend webhook. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_email_identity")
public class CreatorEmailIdentity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private UUID tenantId;

    @Column(name = "local_part", nullable = false, length = 64)
    private String localPart;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /** Rendered "From" name -- may change when the tenant renames themselves. The email address
     * itself must NOT change on rename (that's the whole point of using the immutable UUID); the
     * caller updating this field is expected to leave {@link #email} and {@link #localPart}
     * untouched. */
    @Column(name = "display_name", length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CreatorEmailIdentityStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
