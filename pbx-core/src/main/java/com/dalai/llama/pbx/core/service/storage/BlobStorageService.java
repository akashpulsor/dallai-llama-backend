package com.dalai.llama.pbx.core.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import jakarta.annotation.PostConstruct;
import java.net.URI;

/**
 * S3-compatible blob storage for recordings + transcripts.
 *
 * Works with:
 *   - MinIO (self-hosted on Hetzner)
 *   - Hetzner Object Storage (S3-compatible)
 *   - AWS S3 (if needed later)
 *
 * Config (application.yml):
 *   dalaillama.storage.endpoint: https://minio.dalaillama.in
 *   dalaillama.storage.bucket: dalaillama-media
 *   dalaillama.storage.access-key: minioadmin
 *   dalaillama.storage.secret-key: minioadmin
 *   dalaillama.storage.region: us-east-1
 *
 * Storage layout:
 *   {tenant_id}/recordings/{yyyy}/{MM}/{callId}.wav
 *   {tenant_id}/transcripts/{yyyy}/{MM}/{callId}.json
 */
@Slf4j
@Service
public class BlobStorageService {

    @Value("${dalaillama.storage.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${dalaillama.storage.bucket:dalaillama-media}")
    private String bucket;

    @Value("${dalaillama.storage.access-key:minioadmin}")
    private String accessKey;

    @Value("${dalaillama.storage.secret-key:minioadmin}")
    private String secretKey;

    @Value("${dalaillama.storage.region:us-east-1}")
    private String region;

    @Value("${dalaillama.storage.public-url:}")
    private String publicUrl;

    private S3Client s3;

    @PostConstruct
    void init() {
        try {
            s3 = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .forcePathStyle(true)  // Required for MinIO
                    .build();
            log.info("BlobStorage initialized: endpoint={} bucket={}", endpoint, bucket);
        } catch (Exception e) {
            log.error("BlobStorage init failed: {} — uploads will be disabled", e.getMessage());
            s3 = null;
        }
    }

    /**
     * Upload bytes to storage. Returns public URL.
     *
     * @param path        Storage path (e.g., "tenant123/recordings/2026/03/callid.wav")
     * @param data        File content
     * @param contentType MIME type (audio/wav, application/json)
     * @return Public URL to access the file
     */
    public String upload(String path, byte[] data, String contentType) {
        if (s3 == null) {
            log.warn("BlobStorage disabled — skipping upload: {}", path);
            return null;
        }

        try {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(path)
                    .contentType(contentType)
                    .build();

            s3.putObject(req, RequestBody.fromBytes(data));

            String url = publicUrl != null && !publicUrl.isBlank()
                    ? publicUrl + "/" + bucket + "/" + path
                    : endpoint + "/" + bucket + "/" + path;

            log.debug("Uploaded: {} ({} bytes) → {}", path, data.length, url);
            return url;
        } catch (Exception e) {
            log.error("Upload failed: {} — {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Get pre-signed URL for temporary access (e.g., recording playback).
     */
    public String getPresignedUrl(String path, int expirySeconds) {
        // For MinIO, use the endpoint URL directly (public bucket) or implement presigning
        return endpoint + "/" + bucket + "/" + path;
    }
}