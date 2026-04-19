package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.List;

/**
 * MinIO Bucket Provisioning — creates tenant-specific buckets.
 *
 * Strategy: One bucket per tenant per purpose.
 *   Bucket naming: {rootPrefix}-{tenantSlug}-{purpose}
 *   Example: dl-acme-corp-call-recordings, dl-acme-corp-voicemail
 *
 * Why per-tenant buckets (not key-prefix isolation):
 *   - IAM policies map cleanly to buckets
 *   - Lifecycle rules (retention days) differ per tenant/plan
 *   - Easier quota enforcement via bucket quotas
 */
@Slf4j
@Service
public class MinioBucketService {

    private final S3Client s3Client;
    private final String rootPrefix;

    private static final List<String> BUCKET_SUFFIXES = List.of(
            "call-recordings", "voicemail", "campaign-exports",
            "bot-knowledge", "cdr-archives"
    );

    public MinioBucketService(
            S3Client s3Client,
            @Value("${dalaillama.minio.bucket-prefix:dl}") String rootPrefix) {
        this.s3Client = s3Client;
        this.rootPrefix = rootPrefix;
    }

    public void provisionBuckets(TenantApp app) {
        String slug = app.getTenant().getSlug();
        log.info("Provisioning MinIO buckets for tenant {}", slug);

        for (String suffix : BUCKET_SUFFIXES) {
            String bucketName = rootPrefix + "-" + slug + "-" + suffix;
            ensureBucket(bucketName);
        }

        // Set lifecycle for recordings based on retention policy
        if (app.getRecordingRetentionDays() != null && app.getRecordingRetentionDays() > 0) {
            setRetentionLifecycle(
                    rootPrefix + "-" + slug + "-call-recordings",
                    app.getRecordingRetentionDays());
        }

        log.info("MinIO buckets provisioned for tenant {}", slug);
    }

    public void removeBuckets(String tenantSlug) {
        for (String suffix : BUCKET_SUFFIXES) {
            String bucketName = rootPrefix + "-" + tenantSlug + "-" + suffix;
            try {
                // Empty then delete
                ListObjectsV2Response objects = s3Client.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(bucketName).build());
                for (S3Object obj : objects.contents()) {
                    s3Client.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucketName).key(obj.key()).build());
                }
                s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
                log.info("Deleted MinIO bucket {}", bucketName);
            } catch (NoSuchBucketException e) {
                log.debug("Bucket {} already gone", bucketName);
            }
        }
    }

    private void ensureBucket(String bucketName) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            log.debug("Bucket {} already exists", bucketName);
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
            log.info("Created MinIO bucket {}", bucketName);
        }
    }

    private void setRetentionLifecycle(String bucketName, int retentionDays) {
        try {
            s3Client.putBucketLifecycleConfiguration(
                    PutBucketLifecycleConfigurationRequest.builder()
                            .bucket(bucketName)
                            .lifecycleConfiguration(BucketLifecycleConfiguration.builder()
                                    .rules(LifecycleRule.builder()
                                            .id("auto-expire")
                                            .status(ExpirationStatus.ENABLED)
                                            .expiration(LifecycleExpiration.builder()
                                                    .days(retentionDays).build())
                                            .filter(LifecycleRuleFilter.builder()
                                                    .prefix("").build())
                                            .build())
                                    .build())
                            .build());
            log.info("Set {}-day retention on {}", retentionDays, bucketName);
        } catch (Exception e) {
            log.warn("Failed to set lifecycle on {}: {}", bucketName, e.getMessage());
        }
    }
}