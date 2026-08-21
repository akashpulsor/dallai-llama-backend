package com.dalai.llama.videogen.service;

import java.util.UUID;

/**
 * Copies the provider's own hosted result (fal.ai's URL, not guaranteed durable or authless)
 * into our MinIO so the UI, export bundles, and post-production-service all depend on storage we
 * own, not a third party's CDN. Doc §13.3's intent ("videos written by the provider to MinIO
 * through pre-signed URLs supplied at dispatch time") without requiring every provider to support
 * write-to-URL -- this fetches after the fact instead, which works uniformly across providers.
 */
public interface VideoAssetPersistenceService {

    PersistedAsset persist(UUID jobId, String providerUrl);

    /** Short-lived signed URL for the UI to actually play/download the clip. */
    String presignedUrl(String bucket, String objectKey);

    record PersistedAsset(String bucket, String objectKey) {
    }
}
