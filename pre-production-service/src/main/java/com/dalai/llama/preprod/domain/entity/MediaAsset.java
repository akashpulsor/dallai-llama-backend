package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.MediaAssetType;
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

/** One shared asset-registry table for every bucket/objectKey this service references --
 * discovered as a DRY fix by reading creator-service's real creator_assets table: every service
 * was duplicating its own bucket/object_key column pair per entity instead of a single registry. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "media_asset")
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "bucket", nullable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 24)
    private MediaAssetType assetType;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
