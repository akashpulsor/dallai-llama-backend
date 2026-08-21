package com.dalai.llama.postprod.service;

import java.util.UUID;

/** Copies a provider-hosted result (lip-synced clip) into our own MinIO -- same reasoning as
 * video-generation-service's VideoAssetPersistenceService: a provider's own URL isn't guaranteed
 * durable or authless. */
public interface AssetPersistenceService {

    PersistedAsset persist(UUID postProductionJobId, String providerUrl);

    String presignedUrl(String bucket, String objectKey);

    record PersistedAsset(String bucket, String objectKey) {
    }
}
