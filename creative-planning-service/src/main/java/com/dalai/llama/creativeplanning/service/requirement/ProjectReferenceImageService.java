package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.ProjectReferenceImage;
import com.dalai.llama.creativeplanning.dto.ProjectReferenceImageView;
import com.dalai.llama.creativeplanning.repository.ProjectReferenceImageRepository;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import com.dalai.llama.creativeplanning.service.ProjectReferenceImageAnalysisService;
import com.dalai.llama.creativeplanning.service.storage.MinioObjectStorage;
import com.dalai.llama.creativeplanning.service.storage.UploadValidation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** "What the client has in mind" images attached directly to a project requirement -- stored at
 * creation time, analyzed only once the requirement is funded (see {@link
 * ReferenceMaterialAnalysisService}), unlike {@code ProductReferenceImageService#upload} which
 * analyzes synchronously. */
@Service
public class ProjectReferenceImageService {

    private final ProjectReferenceImageRepository projectReferenceImageRepository;
    private final ProjectReferenceImageAnalysisService projectReferenceImageAnalysisService;
    private final MinioObjectStorage minioObjectStorage;
    private final String referenceImagePrefix;
    private final long maxUploadSizeBytes;

    public ProjectReferenceImageService(
            ProjectReferenceImageRepository projectReferenceImageRepository,
            ProjectReferenceImageAnalysisService projectReferenceImageAnalysisService,
            MinioObjectStorage minioObjectStorage,
            @Value("${creative-planning.minio.reference-image-prefix}") String referenceImagePrefix,
            @Value("${creative-planning.minio.max-upload-size-mb}") long maxUploadSizeMb
    ) {
        this.projectReferenceImageRepository = projectReferenceImageRepository;
        this.projectReferenceImageAnalysisService = projectReferenceImageAnalysisService;
        this.minioObjectStorage = minioObjectStorage;
        this.referenceImagePrefix = referenceImagePrefix;
        this.maxUploadSizeBytes = maxUploadSizeMb * 1024 * 1024;
    }

    @Transactional
    public ProjectReferenceImageView store(UUID tenantId, UUID requirementId, MultipartFile file) {
        UploadValidation.requireNonEmpty(file, "reference image");
        UploadValidation.requireWithinSize(file, maxUploadSizeBytes);
        String contentType = UploadValidation.contentTypeOrDefault(file, "image/jpeg");
        String objectKey = "%s/%s/%s.%s".formatted(
                referenceImagePrefix, requirementId, UUID.randomUUID(), UploadValidation.extensionFor(contentType));

        try {
            minioObjectStorage.uploadMultipart(objectKey, file, contentType);
        } catch (Exception ex) {
            throw CreativePlanningException.upstream("Could not store project reference image: " + ex.getMessage());
        }

        ProjectReferenceImage image = projectReferenceImageRepository.save(ProjectReferenceImage.builder()
                .tenantId(tenantId)
                .projectRequirementId(requirementId)
                .bucket(minioObjectStorage.bucket())
                .objectKey(objectKey)
                .createdAt(OffsetDateTime.now())
                .build());

        return new ProjectReferenceImageView(image.getId(), image.getProjectRequirementId(), image.getBucket(), image.getObjectKey(),
                minioObjectStorage.signedUrl(image.getObjectKey()), null);
    }

    @Transactional(readOnly = true)
    public List<ProjectReferenceImageView> list(UUID requirementId) {
        return projectReferenceImageRepository.findByProjectRequirementId(requirementId).stream()
                .map(image -> new ProjectReferenceImageView(image.getId(), image.getProjectRequirementId(), image.getBucket(),
                        image.getObjectKey(), minioObjectStorage.signedUrl(image.getObjectKey()),
                        projectReferenceImageAnalysisService.getIfPresent(image.getId())))
                .collect(Collectors.toList());
    }
}
