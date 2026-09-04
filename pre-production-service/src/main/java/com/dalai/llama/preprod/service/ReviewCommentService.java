package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.ReviewComment;
import com.dalai.llama.preprod.dto.ReviewCommentView;
import com.dalai.llama.preprod.repository.ReviewCommentRepository;
import com.dalai.llama.preprod.service.chat.ChatServiceClient;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The client review page's comment feed: a comment plus an optional reference image, left while a
 * review is open (see {@link ClientReviewSessionService}), visible to the creator on their own
 * project page. Storage/CRUD stays entirely in this table -- but each comment is also forwarded
 * (best-effort, via the same {@link ChatServiceClient} pre-production-service already uses to
 * ingest a locked project's own content) into chat-service's embedding index, scoped to this
 * project, so the project's AI chat can be asked about client feedback too.
 */
@Service
public class ReviewCommentService {

    private final ReviewCommentRepository reviewCommentRepository;
    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;
    private final ChatServiceClient chatServiceClient;
    private final String bucket;
    private final String objectPrefix;

    public ReviewCommentService(
            ReviewCommentRepository reviewCommentRepository,
            MinioClient minioClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            ChatServiceClient chatServiceClient,
            @Value("${pre-production.minio.bucket}") String bucket,
            @Value("${pre-production.minio.review-comment-prefix}") String objectPrefix
    ) {
        this.reviewCommentRepository = reviewCommentRepository;
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
        this.chatServiceClient = chatServiceClient;
        this.bucket = bucket;
        this.objectPrefix = objectPrefix;
    }

    /** {@code image} is optional -- a plain text comment still posts with no attachment. */
    @Transactional
    public ReviewCommentView add(UUID tenantId, UUID projectId, UUID reviewId, String content, MultipartFile image) {
        if (content == null || content.isBlank()) {
            throw PreProductionException.badRequest("A comment needs some text");
        }
        String imageBucket = null;
        String imageObjectKey = null;
        if (image != null && !image.isEmpty()) {
            String contentType = image.getContentType() == null ? "image/jpeg" : image.getContentType();
            imageObjectKey = "%s/%s/%s.%s".formatted(objectPrefix, projectId, UUID.randomUUID(), extensionFor(contentType));
            imageBucket = bucket;
            upload(imageObjectKey, image, contentType);
        }
        OffsetDateTime now = OffsetDateTime.now();
        ReviewComment comment = reviewCommentRepository.save(ReviewComment.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .reviewId(reviewId)
                .content(content)
                .imageBucket(imageBucket)
                .imageObjectKey(imageObjectKey)
                .resolved(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
        chatServiceClient.ingest(tenantId, comment.getId().toString(), "REVIEW_COMMENT", projectId, content);
        return toView(comment);
    }

    @Transactional(readOnly = true)
    public List<ReviewCommentView> list(UUID tenantId, UUID projectId) {
        return reviewCommentRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(c -> c.getTenantId().equals(tenantId))
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Transactional
    public ReviewCommentView resolve(UUID tenantId, UUID projectId, UUID commentId) {
        ReviewComment comment = reviewCommentRepository.findById(commentId)
                .filter(c -> c.getTenantId().equals(tenantId) && c.getProjectId().equals(projectId))
                .orElseThrow(() -> PreProductionException.notFound("No review comment " + commentId));
        comment.setResolved(true);
        comment.setUpdatedAt(OffsetDateTime.now());
        return toView(reviewCommentRepository.save(comment));
    }

    private String extensionFor(String contentType) {
        int slash = contentType.indexOf('/');
        return slash < 0 ? "jpg" : contentType.substring(slash + 1);
    }

    private void upload(String objectKey, MultipartFile file, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not upload review comment image to MinIO: " + ex.getMessage());
        }
    }

    private String signedUrl(String sourceBucket, String objectKey) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(sourceBucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not sign review comment image URL: " + ex.getMessage());
        }
    }

    private ReviewCommentView toView(ReviewComment comment) {
        String imageUrl = comment.getImageObjectKey() == null ? null : signedUrl(comment.getImageBucket(), comment.getImageObjectKey());
        return new ReviewCommentView(comment.getId(), comment.getContent(), imageUrl, comment.isResolved(), comment.getCreatedAt());
    }
}
