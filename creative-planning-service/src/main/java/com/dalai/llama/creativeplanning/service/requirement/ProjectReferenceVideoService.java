package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.ProjectReferenceVideo;
import com.dalai.llama.creativeplanning.dto.ProjectReferenceVideoView;
import com.dalai.llama.creativeplanning.repository.ProjectReferenceVideoRepository;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import com.dalai.llama.creativeplanning.service.storage.MinioObjectStorage;
import com.dalai.llama.creativeplanning.service.storage.UploadValidation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Client-uploaded reference video clips attached to a project requirement. Same lifecycle
 * shape as {@link ProjectReferenceImageService} -- store on upload, list on read -- but no
 * analysis step: the LLM script prompt reads the requirement's {@code videoShotsIntent} text
 * field, not any vision analysis of the clip. Deliberately capped at a smaller per-file size
 * than image uploads (default 5 MB) since these are meant to be short reference snippets, not
 * feature-length footage. */
@Service
public class ProjectReferenceVideoService {

    private final ProjectReferenceVideoRepository projectReferenceVideoRepository;
    private final MinioObjectStorage minioObjectStorage;
    private final String referenceVideoPrefix;
    private final long maxUploadSizeBytes;

    public ProjectReferenceVideoService(
            ProjectReferenceVideoRepository projectReferenceVideoRepository,
            MinioObjectStorage minioObjectStorage,
            @Value("${creative-planning.minio.reference-video-prefix:reference-videos}") String referenceVideoPrefix,
            @Value("${creative-planning.minio.max-video-upload-size-mb:5}") long maxUploadSizeMb
    ) {
        this.projectReferenceVideoRepository = projectReferenceVideoRepository;
        this.minioObjectStorage = minioObjectStorage;
        this.referenceVideoPrefix = referenceVideoPrefix;
        this.maxUploadSizeBytes = maxUploadSizeMb * 1024 * 1024;
    }

    @Transactional
    public ProjectReferenceVideoView store(UUID tenantId, UUID requirementId, MultipartFile file) {
        UploadValidation.requireNonEmpty(file, "reference video");
        UploadValidation.requireWithinSize(file, maxUploadSizeBytes);
        String contentType = UploadValidation.contentTypeOrDefault(file, "video/mp4");
        if (!contentType.startsWith("video/")) {
            throw CreativePlanningException.badRequest("Only video files are allowed here");
        }
        String objectKey = "%s/%s/%s.%s".formatted(
                referenceVideoPrefix, requirementId, UUID.randomUUID(), UploadValidation.extensionFor(contentType));

        try {
            minioObjectStorage.uploadMultipart(objectKey, file, contentType);
        } catch (Exception ex) {
            throw CreativePlanningException.upstream("Could not store project reference video: " + ex.getMessage());
        }

        ProjectReferenceVideo video = projectReferenceVideoRepository.save(ProjectReferenceVideo.builder()
                .tenantId(tenantId)
                .projectRequirementId(requirementId)
                .bucket(minioObjectStorage.bucket())
                .objectKey(objectKey)
                .originalFilename(file.getOriginalFilename())
                .contentType(contentType)
                .sizeBytes(file.getSize())
                .createdAt(OffsetDateTime.now())
                .build());

        return toView(video);
    }

    @Transactional(readOnly = true)
    public List<ProjectReferenceVideoView> list(UUID requirementId) {
        return projectReferenceVideoRepository.findByProjectRequirementId(requirementId).stream()
                .map(this::toView).toList();
    }

    private ProjectReferenceVideoView toView(ProjectReferenceVideo v) {
        return new ProjectReferenceVideoView(v.getId(), v.getProjectRequirementId(), v.getBucket(), v.getObjectKey(),
                minioObjectStorage.signedUrl(v.getObjectKey()), v.getOriginalFilename(), v.getContentType(), v.getSizeBytes());
    }
}
