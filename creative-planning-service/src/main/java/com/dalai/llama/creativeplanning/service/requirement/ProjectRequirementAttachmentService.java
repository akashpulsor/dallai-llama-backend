package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirementAttachment;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementAttachmentView;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementAttachmentRepository;
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

/** Reference-image/detail attachments on a standalone requirement (entry point B has no product
 * to hang images off of, unlike {@code ProductReferenceImageService}). No vision analysis here --
 * that's specific to the branded-product flow; a standalone brief's attachments are just
 * supporting material for whoever reviews the requirement. */
@Service
public class ProjectRequirementAttachmentService {

    private final ProjectRequirementAttachmentRepository projectRequirementAttachmentRepository;
    private final ProjectRequirementService projectRequirementService;
    private final MinioObjectStorage minioObjectStorage;
    private final String attachmentPrefix;
    private final long maxUploadSizeBytes;

    public ProjectRequirementAttachmentService(
            ProjectRequirementAttachmentRepository projectRequirementAttachmentRepository,
            ProjectRequirementService projectRequirementService,
            MinioObjectStorage minioObjectStorage,
            @Value("${creative-planning.minio.requirement-attachment-prefix}") String attachmentPrefix,
            @Value("${creative-planning.minio.max-upload-size-mb}") long maxUploadSizeMb
    ) {
        this.projectRequirementAttachmentRepository = projectRequirementAttachmentRepository;
        this.projectRequirementService = projectRequirementService;
        this.minioObjectStorage = minioObjectStorage;
        this.attachmentPrefix = attachmentPrefix;
        this.maxUploadSizeBytes = maxUploadSizeMb * 1024 * 1024;
    }

    @Transactional
    public ProjectRequirementAttachmentView upload(UUID tenantId, UUID requirementId, MultipartFile file) {
        ProjectRequirement requirement = projectRequirementService.requireRequirement(tenantId, requirementId);
        UploadValidation.requireNonEmpty(file, "attachment");
        UploadValidation.requireWithinSize(file, maxUploadSizeBytes);
        String contentType = UploadValidation.contentTypeOrDefault(file, "application/octet-stream");
        String objectKey = "%s/%s/%s.%s".formatted(
                attachmentPrefix, requirement.getId(), UUID.randomUUID(), UploadValidation.extensionFor(contentType));

        minioObjectStorage.uploadMultipart(objectKey, file, contentType);

        ProjectRequirementAttachment attachment = projectRequirementAttachmentRepository.save(ProjectRequirementAttachment.builder()
                .tenantId(tenantId)
                .requirementId(requirement.getId())
                .bucket(minioObjectStorage.bucket())
                .objectKey(objectKey)
                .createdAt(OffsetDateTime.now())
                .build());

        return toView(attachment);
    }

    @Transactional(readOnly = true)
    public List<ProjectRequirementAttachmentView> list(UUID tenantId, UUID requirementId) {
        projectRequirementService.requireRequirement(tenantId, requirementId);
        return projectRequirementAttachmentRepository.findByRequirementId(requirementId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    private ProjectRequirementAttachmentView toView(ProjectRequirementAttachment attachment) {
        return new ProjectRequirementAttachmentView(attachment.getId(), attachment.getBucket(), attachment.getObjectKey());
    }
}
