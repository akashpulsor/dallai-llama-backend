package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotReferenceImage;
import com.dalai.llama.preprod.dto.ShotReferenceImageView;
import com.dalai.llama.preprod.repository.ShotReferenceImageRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Uploads and reads the multi-image reference bundle for a shot flagged needs_multi_image
 * (propagated from its screenplay scene -- see V65/V66). One creator upload call = N new
 * shot_reference_image rows in creator-set order, stored in MinIO under a per-shot prefix so
 * the storyboard tile, PDF exporter, and video-gen prompt can walk them by shot id without
 * having to name each file.
 *
 * <p>Only the shot's own tenant can upload/list/delete -- guarded by looking up the shot with
 * the tenant id before touching any reference-image row. A per-file cap of 15 MB matches the
 * existing storyboard image size ceiling; each request accepts up to 20 files to keep one
 * request's total under the multipart/max-request-size budget the app sets.
 */
@Slf4j
@Service
public class ShotReferenceImageService {

    private static final int MAX_FILES_PER_REQUEST = 20;
    private static final long MAX_FILE_BYTES = 15L * 1024 * 1024;

    private final ShotRepository shotRepository;
    private final ShotReferenceImageRepository shotReferenceImageRepository;
    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;
    private final String bucket;
    private final String castMediaPrefix;

    public ShotReferenceImageService(
            ShotRepository shotRepository,
            ShotReferenceImageRepository shotReferenceImageRepository,
            MinioClient minioClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.cast-media-prefix}") String castMediaPrefix
    ) {
        this.shotRepository = shotRepository;
        this.shotReferenceImageRepository = shotReferenceImageRepository;
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
        this.bucket = bucket;
        this.castMediaPrefix = castMediaPrefix;
    }

    @Transactional
    public List<ShotReferenceImageView> upload(UUID tenantId, UUID shotId, List<MultipartFile> files, List<String> captions) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        if (files == null || files.isEmpty()) {
            throw PreProductionException.badRequest("Upload at least one file");
        }
        if (files.size() > MAX_FILES_PER_REQUEST) {
            throw PreProductionException.badRequest(
                    "Too many files in one upload (max " + MAX_FILES_PER_REQUEST + ")");
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw PreProductionException.badRequest("One of the uploaded files is empty");
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw PreProductionException.badRequest(
                        "File " + file.getOriginalFilename() + " exceeds " + (MAX_FILE_BYTES / 1024 / 1024) + " MB");
            }
        }

        int startOrdinal = shotReferenceImageRepository.findByShotIdOrderByOrdinalAsc(shotId).stream()
                .mapToInt(ShotReferenceImage::getOrdinal)
                .max()
                .orElse(-1) + 1;
        OffsetDateTime now = OffsetDateTime.now();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String contentType = file.getContentType() == null ? "image/png" : file.getContentType();
            String extension = extensionFor(file.getOriginalFilename(), contentType);
            String objectKey = "%s/shot-refs/%s/%s.%s".formatted(
                    castMediaPrefix, shot.getId(), UUID.randomUUID(), extension);
            try {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(contentType)
                        .build());
            } catch (Exception ex) {
                throw PreProductionException.upstream(
                        "Could not upload shot reference image to MinIO: " + ex.getMessage());
            }

            String caption = captions != null && i < captions.size() ? captions.get(i) : null;
            shotReferenceImageRepository.save(ShotReferenceImage.builder()
                    .id(UUID.randomUUID())
                    .tenantId(tenantId)
                    .shotId(shot.getId())
                    .bucket(bucket)
                    .objectKey(objectKey)
                    .contentType(contentType)
                    .caption(caption == null || caption.isBlank() ? null : caption.trim())
                    .ordinal(startOrdinal + i)
                    .createdAt(now)
                    .build());
        }
        return list(tenantId, shotId);
    }

    @Transactional(readOnly = true)
    public List<ShotReferenceImageView> list(UUID tenantId, UUID shotId) {
        shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        return shotReferenceImageRepository.findByShotIdOrderByOrdinalAsc(shotId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public void delete(UUID tenantId, UUID shotId, UUID imageId) {
        ShotReferenceImage image = shotReferenceImageRepository.findById(imageId)
                .filter(r -> r.getTenantId().equals(tenantId) && r.getShotId().equals(shotId))
                .orElseThrow(() -> PreProductionException.notFound(
                        "No reference image " + imageId + " on shot " + shotId));
        // Best-effort MinIO cleanup -- if the object is already gone the row is still safe to
        // delete; failing here would leave the row pointing at a missing object, which is
        // worse than a rare orphan blob.
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(image.getBucket())
                    .object(image.getObjectKey())
                    .build());
        } catch (Exception ex) {
            log.warn("MinIO object delete failed for shot reference image {} ({}): {}",
                    imageId, image.getObjectKey(), ex.getMessage());
        }
        shotReferenceImageRepository.delete(image);
    }

    private ShotReferenceImageView toView(ShotReferenceImage image) {
        return new ShotReferenceImageView(
                image.getId(), image.getShotId(),
                image.getBucket(), image.getObjectKey(), signedUrl(image.getBucket(), image.getObjectKey()),
                image.getContentType(), image.getCaption(), image.getOrdinal(), image.getCreatedAt());
    }

    /** Presigned browser-accessible URL for one-hour display. Nullable on presign failure --
     * matches CastProfileService's own signedUrl helper's degrade-to-no-thumbnail contract. */
    private String signedUrl(String bucket, String objectKey) {
        if (bucket == null || objectKey == null) {
            return null;
        }
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            return null;
        }
    }

    private String extensionFor(String originalFilename, String contentType) {
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
        }
        int slash = contentType.indexOf('/');
        return slash < 0 ? "bin" : contentType.substring(slash + 1);
    }
}
