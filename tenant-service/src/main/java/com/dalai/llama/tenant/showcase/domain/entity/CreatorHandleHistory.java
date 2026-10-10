package com.dalai.llama.tenant.showcase.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A handle a creator gave up. Nobody else may claim it until the redirect window after
 * {@link #releasedAt} has passed, so old links keep pointing at the right creator. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_handle_history")
public class CreatorHandleHistory {

    @Id
    @Column(name = "handle", length = 40)
    private String handle;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "released_at", nullable = false)
    private OffsetDateTime releasedAt;
}
