package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.io.InputStream;
import java.nio.file.Path;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AssetStorageService {

    private static final int STREAM_UPLOAD_PART_SIZE_BYTES = 8 * 1024 * 1024;

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

    public void downloadObjectToPath(String bucket, String objectKey, Path targetPath) {
        s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build(),
                targetPath
        );
    }

    public StreamedObject openObjectStream(String bucket, String objectKey) {
        var inputStream = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build());
        var response = inputStream.response();
        return new StreamedObject(
                inputStream,
                response.contentType(),
                response.contentLength() == null ? -1L : response.contentLength()
        );
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

    public StoredObject uploadCreatorAssetFromPath(String objectKey, Path sourcePath, String contentType, Duration signedUrlTtl) {
        String bucket = creatorAssetsBucket();
        ensureBucket(bucket);
        try {
            long size = Files.size(sourcePath);
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(contentType)
                            .contentLength(size)
                            .build(),
                    RequestBody.fromFile(sourcePath)
            );
            return new StoredObject(bucket, objectKey, contentType, size, signedUrl(bucket, objectKey, signedUrlTtl));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read source file for upload.", ex);
        }
    }

    public StoredObject uploadCreatorAssetFromStream(String objectKey, InputStream inputStream, String contentType, Duration signedUrlTtl) {
        String bucket = creatorAssetsBucket();
        ensureBucket(bucket);
        String uploadId = "";
        try {
            var createResponse = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType)
                    .build());
            uploadId = createResponse.uploadId();
            List<CompletedPart> completedParts = new ArrayList<>();
            byte[] buffer = new byte[STREAM_UPLOAD_PART_SIZE_BYTES];
            int partNumber = 1;
            int bytesRead;
            while ((bytesRead = fillPart(inputStream, buffer)) > 0) {
                byte[] partBytes = Arrays.copyOf(buffer, bytesRead);
                var uploadResponse = s3Client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucket)
                                .key(objectKey)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength((long) bytesRead)
                                .build(),
                        RequestBody.fromBytes(partBytes)
                );
                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(uploadResponse.eTag())
                        .build());
                partNumber++;
            }
            if (completedParts.isEmpty()) {
                throw new IllegalStateException("Creator asset stream was empty.");
            }
            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return new StoredObject(
                    bucket,
                    objectKey,
                    head.contentType() == null || head.contentType().isBlank() ? contentType : head.contentType(),
                    head.contentLength(),
                    signedUrl(bucket, objectKey, signedUrlTtl)
            );
        } catch (IOException ex) {
            abortMultipartUploadQuietly(bucket, objectKey, uploadId);
            throw new IllegalStateException("Could not stream creator asset to storage.", ex);
        } catch (RuntimeException ex) {
            abortMultipartUploadQuietly(bucket, objectKey, uploadId);
            throw ex;
        }
    }

    public StoredObject putCreatorObject(String objectKey, byte[] bytes, String contentType) {
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
        return new StoredObject(bucket, objectKey, contentType, (long) bytes.length, "");
    }

    public StoredObject putCreatorObjectFromPath(String objectKey, Path sourcePath, String contentType) {
        String bucket = creatorAssetsBucket();
        ensureBucket(bucket);
        try {
            long size = Files.size(sourcePath);
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(contentType)
                            .contentLength(size)
                            .build(),
                    RequestBody.fromFile(sourcePath)
            );
            return new StoredObject(bucket, objectKey, contentType, size, "");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read source file for object upload.", ex);
        }
    }

    public MultipartUpload createCreatorMultipartUpload(String objectKey, String contentType) {
        String bucket = creatorAssetsBucket();
        ensureBucket(bucket);
        var response = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .build());
        return new MultipartUpload(bucket, objectKey, response.uploadId());
    }

    public String presignedCreatorUploadPartUrl(String objectKey, String multipartUploadId, int partNumber, Duration ttl) {
        String bucket = creatorAssetsBucket();
        ensureBucket(bucket);
        UploadPartRequest request = UploadPartRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .uploadId(multipartUploadId)
                .partNumber(partNumber)
                .build();
        UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                .signatureDuration(ttl)
                .uploadPartRequest(request)
                .build();
        return s3Presigner.presignUploadPart(presignRequest).url().toString();
    }

    public StoredObject completeCreatorMultipartUpload(
            String objectKey,
            String multipartUploadId,
            int expectedPartCount,
            String contentType,
            Long declaredSizeBytes,
            Duration signedUrlTtl
    ) {
        String bucket = creatorAssetsBucket();
        ensureBucket(bucket);
        List<Part> uploadedParts = listMultipartParts(bucket, objectKey, multipartUploadId);
        List<Integer> missing = missingPartNumbers(uploadedParts, expectedPartCount);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Multipart upload is missing parts: " + missing.stream().limit(20).toList());
        }
        long uploadedSizeBytes = uploadedParts.stream()
                .map(Part::size)
                .filter(size -> size != null && size > 0)
                .mapToLong(Long::longValue)
                .sum();
        if (declaredSizeBytes != null && declaredSizeBytes > 0 && uploadedSizeBytes > 0 && uploadedSizeBytes != declaredSizeBytes) {
            throw new IllegalStateException(
                    "Multipart upload size mismatch. Expected " + declaredSizeBytes + " bytes but received " + uploadedSizeBytes + "."
            );
        }
        List<CompletedPart> completedParts = uploadedParts.stream()
                .sorted(Comparator.comparingInt(Part::partNumber))
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.eTag())
                        .build())
                .toList();
        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .uploadId(multipartUploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build());
        HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
        return new StoredObject(
                bucket,
                objectKey,
                head.contentType() == null || head.contentType().isBlank() ? contentType : head.contentType(),
                head.contentLength(),
                signedUrl(bucket, objectKey, signedUrlTtl)
        );
    }

    public void abortCreatorMultipartUpload(String objectKey, String multipartUploadId) {
        String bucket = creatorAssetsBucket();
        ensureBucket(bucket);
        s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .uploadId(multipartUploadId)
                .build());
    }

    public void downloadCreatorObjectToOutputStream(String objectKey, OutputStream outputStream) throws IOException {
        downloadObjectToOutputStream(creatorAssetsBucket(), objectKey, outputStream);
    }

    public void downloadObjectToOutputStream(String bucket, String objectKey, OutputStream outputStream) throws IOException {
        try (var inputStream = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build())) {
            inputStream.transferTo(outputStream);
        }
    }

    public boolean creatorObjectExists(String objectKey) {
        String bucket = creatorAssetsBucket();
        ensureBucket(bucket);
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return true;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return false;
            }
            throw ex;
        }
    }

    public void deleteCreatorObject(String objectKey) {
        String bucket = creatorAssetsBucket();
        ensureBucket(bucket);
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
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

    private List<Part> listMultipartParts(String bucket, String objectKey, String multipartUploadId) {
        List<Part> parts = new ArrayList<>();
        Integer marker = null;
        boolean truncated;
        do {
            ListPartsRequest.Builder builder = ListPartsRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .uploadId(multipartUploadId);
            if (marker != null) {
                builder.partNumberMarker(marker);
            }
            var response = s3Client.listParts(builder.build());
            parts.addAll(response.parts());
            marker = response.nextPartNumberMarker();
            truncated = Boolean.TRUE.equals(response.isTruncated());
        } while (truncated);
        return parts;
    }

    private List<Integer> missingPartNumbers(List<Part> uploadedParts, int expectedPartCount) {
        Set<Integer> uploaded = ConcurrentHashMap.newKeySet();
        for (Part part : uploadedParts) {
            if (part.partNumber() != null) {
                uploaded.add(part.partNumber());
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int partNumber = 1; partNumber <= expectedPartCount; partNumber++) {
            if (!uploaded.contains(partNumber)) {
                missing.add(partNumber);
            }
        }
        return missing;
    }

    private int fillPart(InputStream inputStream, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = inputStream.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            offset += read;
        }
        return offset;
    }

    public record StreamedObject(InputStream inputStream, String contentType, long sizeBytes) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }

    private void abortMultipartUploadQuietly(String bucket, String objectKey, String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            return;
        }
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .uploadId(uploadId)
                    .build());
        } catch (RuntimeException ignored) {
            // Best-effort cleanup.
        }
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

    public record MultipartUpload(
            String bucket,
            String objectKey,
            String uploadId
    ) {
    }
}
