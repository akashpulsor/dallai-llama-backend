package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AssetStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final CreatorProperties properties;
    private final Set<String> checkedBuckets = ConcurrentHashMap.newKeySet();

    public AssetStorageService(S3Client s3Client, S3Presigner s3Presigner, CreatorProperties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
    }

    public S3Client s3Client() {
        return s3Client;
    }

    public String creatorAssetsBucket() {
        return properties.getStorage().getCreatorAssetsBucket();
    }

    public String creatorExportsBucket() {
        return properties.getStorage().getCreatorExportsBucket();
    }

    public StoredObject uploadCreatorAsset(String objectKey, byte[] bytes, String contentType, Duration signedUrlTtl) {
        String bucket = creatorAssetsBucket();
        ensureBucket(bucket);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes)
        );
        return new StoredObject(
                bucket,
                objectKey,
                contentType,
                (long) bytes.length,
                signedUrl(bucket, objectKey, signedUrlTtl)
        );
    }

    public String signedUrl(String bucket, String objectKey, Duration ttl) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(request)
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    private void ensureBucket(String bucket) {
        if (checkedBuckets.contains(bucket)) {
            return;
        }
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) {
                throw ex;
            }
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (S3Exception createEx) {
                if (createEx.statusCode() != 409) {
                    throw createEx;
                }
            }
        }
        checkedBuckets.add(bucket);
    }

    public record StoredObject(
            String bucket,
            String objectKey,
            String contentType,
            Long sizeBytes,
            String signedUrl
    ) {
    }
}
