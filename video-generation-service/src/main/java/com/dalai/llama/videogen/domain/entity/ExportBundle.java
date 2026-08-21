package com.dalai.llama.videogen.domain.entity;

import com.dalai.llama.videogen.domain.ExportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "export_bundle")
public class ExportBundle {

    @Id
    @Column(name = "bundle_id")
    private UUID bundleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "prompt_id", nullable = false)
    private UUID promptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExportStatus status;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
