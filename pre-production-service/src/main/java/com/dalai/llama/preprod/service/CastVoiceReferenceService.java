package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CastProfileType;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.UUID;

/**
 * Opens a cast voice sample only after tenant, project and cast-assignment checks. This is the
 * internal boundary used by llm-gateway before it uploads the original sample to a voice provider.
 */
@Service
public class CastVoiceReferenceService {

    private final CastProfileRepository castProfileRepository;
    private final CastAssignmentRepository castAssignmentRepository;
    private final ProjectRepository projectRepository;
    private final MinioClient minioClient;

    public CastVoiceReferenceService(
            CastProfileRepository castProfileRepository,
            CastAssignmentRepository castAssignmentRepository,
            ProjectRepository projectRepository,
            MinioClient minioClient
    ) {
        this.castProfileRepository = castProfileRepository;
        this.castAssignmentRepository = castAssignmentRepository;
        this.projectRepository = projectRepository;
        this.minioClient = minioClient;
    }

    @Transactional(readOnly = true)
    public VoiceReference open(UUID tenantId, UUID projectId, UUID castProfileId) {
        projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        CastProfile profile = castProfileRepository.findByIdAndTenantId(castProfileId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No cast profile " + castProfileId));
        if (profile.getProfileType() != CastProfileType.ACTOR) {
            throw PreProductionException.badRequest("Cast profile " + castProfileId + " is not an ACTOR profile");
        }
        if (profile.getProjectId() != null && !projectId.equals(profile.getProjectId())) {
            throw PreProductionException.notFound("Cast profile " + castProfileId + " is not available in project " + projectId);
        }
        if (profile.getProjectId() == null
                && !castAssignmentRepository.existsByProjectIdAndCastProfileId(projectId, castProfileId)) {
            throw PreProductionException.notFound("Cast profile " + castProfileId + " is not assigned to project " + projectId);
        }
        if (!hasText(profile.getVoiceRefBucket()) || !hasText(profile.getVoiceRefObjectKey())) {
            throw PreProductionException.badRequest("Cast profile " + castProfileId + " has no voice reference sample");
        }
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(profile.getVoiceRefBucket())
                    .object(profile.getVoiceRefObjectKey())
                    .build());
            GetObjectResponse object = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(profile.getVoiceRefBucket())
                    .object(profile.getVoiceRefObjectKey())
                    .build());
            return new VoiceReference(object, filename(profile.getVoiceRefObjectKey()), stat.contentType(), stat.size());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not read cast voice reference: " + ex.getMessage(), ex);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String filename(String objectKey) {
        int separator = Math.max(objectKey.lastIndexOf('/'), objectKey.lastIndexOf('\\'));
        return separator < 0 ? objectKey : objectKey.substring(separator + 1);
    }

    public record VoiceReference(InputStream content, String filename, String contentType, long contentLength) {
    }
}
