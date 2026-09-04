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

    /** Streams an already-persisted MinIO object to a local file -- the download half of
     * {@link com.dalai.llama.videogen.service.FinalRenderService}'s per-shot ffmpeg concat.
     * Sibling of {@link #persist}: same MinIO client, opposite direction. Fails fast if the
     * object is missing (not the same "silent truncated success" trap the network-stream
     * upload path guards against, since a local FileOutputStream never partially closes on us). */
    void downloadTo(String bucket, String objectKey, java.nio.file.Path destination);

    /** Uploads a local file to MinIO under the given key -- the upload half of
     * {@link com.dalai.llama.videogen.service.FinalRenderService}. Simpler than
     * {@link #persist} because the file's length is known up-front (no unknown-size
     * streaming needed). */
    PersistedAsset uploadFile(String bucket, String objectKey, java.nio.file.Path source);

    record PersistedAsset(String bucket, String objectKey) {
    }
}
