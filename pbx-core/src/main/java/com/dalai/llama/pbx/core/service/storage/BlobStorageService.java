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
 * Storage layout per bucket:
 *   call-recordings:   {tenant_id}/{yyyy}/{MM}/{callId}.wav
 *   cdr-archives:      {tenant_id}/{yyyy}/{MM}/{callId}.json
 *   voicemail:          {tenant_id}/{yyyy}/{MM}/{callId}.wav
 *   campaign-exports:   {tenant_id}/campaigns/{campaignId}/export.csv
 *   bot-knowledge:      {tenant_id}/bots/{botId}/{docId}.txt
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

    // ── Bucket names from config ──
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
    // CONVENIENCE METHODS — use configured bucket names
    // ═══════════════════════════════════════════════════════════

    /**
     * Upload call recording.
     * Path: {tenantId}/{yyyy}/{MM}/{callId}.wav
     */
    public String uploadRecording(String tenantId, String callId, byte[] audioData) {
        String path = buildDatePath(tenantId, callId, "wav");
        return upload(recordingsBucket, path, audioData, "audio/wav");
    }

    /**
     * Upload call transcript (final JSON).
     * Path: {tenantId}/{yyyy}/{MM}/{callId}.json
     */
    public String uploadTranscript(String tenantId, String callId, byte[] jsonData) {
        String path = buildDatePath(tenantId, callId, "json");
        return upload(cdrBucket, path, jsonData, "application/json");
    }

    /**
     * Upload voicemail recording.
     */
    public String uploadVoicemail(String tenantId, String callId, byte[] audioData) {
        String path = buildDatePath(tenantId, callId, "wav");
        return upload(voicemailBucket, path, audioData, "audio/wav");
    }

    /**
     * Upload campaign export CSV.
     */
    public String uploadCampaignExport(String tenantId, String campaignId, byte[] csvData) {
        String path = tenantId + "/campaigns/" + campaignId + "/export.csv";
        return upload(exportsBucket, path, csvData, "text/csv");
    }

    /**
     * Upload bot knowledge document.
     */
    public String uploadKnowledgeDoc(String tenantId, String botId, String docId, byte[] content) {
        String path = tenantId + "/bots/" + botId + "/" + docId + ".txt";
        return upload(knowledgeBucket, path, content, "text/plain");
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
    public String getRecordingUrl(String tenantId, String callId) {
        String path = buildDatePath(tenantId, callId, "wav");
        return getPresignedUrl(recordingsBucket, path, 3600);
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
     * Build date-partitioned path: {tenantId}/{yyyy}/{MM}/{fileName}.{ext}
     */
    private String buildDatePath(String tenantId, String fileName, String ext) {
        var now = java.time.LocalDate.now();
        return "%s/%04d/%02d/%s.%s".formatted(tenantId, now.getYear(), now.getMonthValue(), fileName, ext);
    }

    private String buildUrl(String bucket, String path) {
        if (publicUrl != null && !publicUrl.isBlank()) {
            return publicUrl + "/" + bucket + "/" + path;
        }
        return endpoint + "/" + bucket + "/" + path;
    }

    // ── Getters for bucket names (other services may need them) ──
    public String getRecordingsBucket() { return recordingsBucket; }
    public String getVoicemailBucket() { return voicemailBucket; }
    public String getExportsBucket() { return exportsBucket; }
    public String getKnowledgeBucket() { return knowledgeBucket; }
    public String getCdrBucket() { return cdrBucket; }
}