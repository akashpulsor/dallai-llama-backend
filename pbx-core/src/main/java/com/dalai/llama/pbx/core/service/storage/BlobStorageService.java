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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;

/**
 * S3-compatible blob storage for recordings, transcripts, voicemail, exports.
 *
 * Per-tenant bucket naming (matches tenant-service MinioBucketService):
 *   Bucket: {prefix}-{tenantSlug}-{suffix}  e.g. dl-acme-call-recordings
 *   Key layout:
 *     call-recordings:   {yyyy}/{MM}/{callId}.wav
 *     cdr-archives:      {yyyy}/{MM}/{callId}.json
 *     voicemail:          {yyyy}/{MM}/{callId}.wav
 *     campaign-exports:   campaigns/{campaignId}/export.csv
 *     bot-knowledge:      bots/{botId}/{docId}.txt
 */
@Slf4j
@Service
public class BlobStorageService {

    @Value("${dalaillama.storage.endpoint:http://minio.infra.svc.cluster.local:9000}")
    private String endpoint;

    @Value("${dalaillama.storage.access-key:dalaillama-admin}")
    private String accessKey;

    @Value("${dalaillama.storage.secret-key:minio-secret-123}")
    private String secretKey;

    @Value("${dalaillama.storage.region:us-east-1}")
    private String region;

    @Value("${dalaillama.storage.public-url:}")
    private String publicUrl;

    @Value("${dalaillama.minio-bucket-prefix:dl}")
    private String bucketPrefix;

    // ── Bucket suffixes (must match tenant-service MinioBucketService BUCKET_SUFFIXES) ──
    @Value("${dalaillama.storage.buckets.recordings:call-recordings}")
    private String recordingsBucket;

    @Value("${dalaillama.storage.buckets.voicemail:voicemail}")
    private String voicemailBucket;
    @Value("${dalaillama.storage.buckets.exports:campaign-exports}")
    private String exportsBucket;

    @Value("${dalaillama.storage.buckets.knowledge:bot-knowledge}")
    private String knowledgeBucket;

    @Value("${dalaillama.storage.buckets.cdr:cdr-archives}")
    private String cdrBucket;

    private S3Client s3;
    private S3Presigner presigner;

    @PostConstruct
    void init() {
        try {
            var creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));

            s3 = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(creds)
                    .forcePathStyle(true)
                    .build();

            presigner = S3Presigner.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.of(region))
                    .credentialsProvider(creds)
                    .build();

            log.info("BlobStorage initialized: endpoint={} buckets=[{},{},{},{},{}]",
                    endpoint, recordingsBucket, voicemailBucket, exportsBucket, knowledgeBucket, cdrBucket);
        } catch (Exception e) {
            log.error("BlobStorage init failed: {} — uploads will be disabled", e.getMessage());
            s3 = null;
            presigner = null;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CONVENIENCE METHODS — per-tenant bucket resolution
    // ═══════════════════════════════════════════════════════════

    /**
     * Upload call recording.
     * Bucket: {prefix}-{tenantSlug}-call-recordings  Key: {yyyy}/{MM}/{callId}.wav
     */
    public String uploadRecording(String tenantSlug, String callId, byte[] audioData) {
        String bucket = resolveBucket(tenantSlug, recordingsBucket);
        String path = buildDatePath(callId, "wav");
        return upload(bucket, path, audioData, "audio/wav");
    }

    /**
     * Upload call transcript (final JSON).
     * Bucket: {prefix}-{tenantSlug}-cdr-archives  Key: {yyyy}/{MM}/{callId}.json
     */
    public String uploadTranscript(String tenantSlug, String callId, byte[] jsonData) {
        String bucket = resolveBucket(tenantSlug, cdrBucket);
        String path = buildDatePath(callId, "json");
        return upload(bucket, path, jsonData, "application/json");
    }

    /**
     * Upload voicemail recording.
     * Bucket: {prefix}-{tenantSlug}-voicemail  Key: {yyyy}/{MM}/{callId}.wav
     */
    public String uploadVoicemail(String tenantSlug, String callId, byte[] audioData) {
        String bucket = resolveBucket(tenantSlug, voicemailBucket);
        String path = buildDatePath(callId, "wav");
        return upload(bucket, path, audioData, "audio/wav");
    }

    /**
     * Upload campaign export CSV.
     * Bucket: {prefix}-{tenantSlug}-campaign-exports  Key: campaigns/{campaignId}/export.csv
     */
    public String uploadCampaignExport(String tenantSlug, String campaignId, byte[] csvData) {
        String bucket = resolveBucket(tenantSlug, exportsBucket);
        String path = "campaigns/" + campaignId + "/export.csv";
        return upload(bucket, path, csvData, "text/csv");
    }

    /**
     * Upload bot knowledge document.
     * Bucket: {prefix}-{tenantSlug}-bot-knowledge  Key: bots/{botId}/{docId}.txt
     */
    public String uploadKnowledgeDoc(String tenantSlug, String botId, String docId, byte[] content) {
        String bucket = resolveBucket(tenantSlug, knowledgeBucket);
        String path = "bots/" + botId + "/" + docId + ".txt";
        return upload(bucket, path, content, "text/plain");
    }

    // ═══════════════════════════════════════════════════════════
    // CORE UPLOAD — accepts any bucket
    // ═══════════════════════════════════════════════════════════

    /**
     * Upload bytes to a specific bucket.
     *
     * @param bucket      Target bucket name
     * @param path        Object key within bucket
     * @param data        File content
     * @param contentType MIME type
     * @return URL to access the file
     */
    public String upload(String bucket, String path, byte[] data, String contentType) {
        if (s3 == null) {
            log.warn("BlobStorage disabled — skipping upload: {}/{}", bucket, path);
            return null;
        }

        try {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(path)
                    .contentType(contentType)
                    .build();

            s3.putObject(req, RequestBody.fromBytes(data));

            String url = buildUrl(bucket, path);
            log.debug("Uploaded: {}/{} ({} bytes)", bucket, path, data.length);
            return url;
        } catch (Exception e) {
            log.error("Upload failed: {}/{} — {}", bucket, path, e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PRESIGNED URLs — for secure temporary access
    // ═══════════════════════════════════════════════════════════

    /**
     * Get presigned URL for recording playback (default 1 hour).
     */
    public String getRecordingUrl(String tenantSlug, String callId) {
        String bucket = resolveBucket(tenantSlug, recordingsBucket);
        String path = buildDatePath(callId, "wav");
        return getPresignedUrl(bucket, path, 3600);
    }

    /**
     * Get presigned URL for any object.
     */
    public String getPresignedUrl(String bucket, String path, int expirySeconds) {
        if (presigner == null) {
            return buildUrl(bucket, path);
        }

        try {
            var presignReq = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(expirySeconds))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(path)
                            .build())
                    .build();

            return presigner.presignGetObject(presignReq).url().toString();
        } catch (Exception e) {
            log.warn("Presign failed: {}/{} — falling back to direct URL", bucket, path);
            return buildUrl(bucket, path);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Resolve per-tenant bucket name: {prefix}-{tenantSlug}-{suffix}
     * Must match tenant-service MinioBucketService naming convention.
     */
    public String resolveBucket(String tenantSlug, String suffix) {
        return bucketPrefix + "-" + tenantSlug + "-" + suffix;
    }

    /**
     * Build date-partitioned key: {yyyy}/{MM}/{fileName}.{ext}
     * No tenantId prefix — bucket itself is tenant-scoped.
     */
    private String buildDatePath(String fileName, String ext) {
        var now = java.time.LocalDate.now();
        return "%04d/%02d/%s.%s".formatted(now.getYear(), now.getMonthValue(), fileName, ext);
    }

    private String buildUrl(String bucket, String path) {
        if (publicUrl != null && !publicUrl.isBlank()) {
            return publicUrl + "/" + bucket + "/" + path;
        }
        return endpoint + "/" + bucket + "/" + path;
    }

    // ── Getters for bucket suffixes (callers combine with resolveBucket) ──
    public String getRecordingsBucketSuffix() { return recordingsBucket; }
    public String getVoicemailBucketSuffix() { return voicemailBucket; }
    public String getExportsBucketSuffix() { return exportsBucket; }
    public String getKnowledgeBucketSuffix() { return knowledgeBucket; }
    public String getCdrBucketSuffix() { return cdrBucket; }
    public String getBucketPrefix() { return bucketPrefix; }
}