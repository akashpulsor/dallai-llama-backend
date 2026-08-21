package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.MediaAssetType;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.CreateCastProfileRequest;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CastProfileService {

    private final CastProfileRepository castProfileRepository;
    private final MediaAssetService mediaAssetService;

    public CastProfileService(CastProfileRepository castProfileRepository, MediaAssetService mediaAssetService) {
        this.castProfileRepository = castProfileRepository;
        this.mediaAssetService = mediaAssetService;
    }

    @Transactional
    public CastProfileView create(UUID tenantId, CreateCastProfileRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        CastProfile profile = castProfileRepository.save(CastProfile.builder()
                .tenantId(tenantId)
                .projectId(request.projectId())
                .displayName(request.displayName())
                .faceRefBucket(request.faceRefBucket())
                .faceRefObjectKey(request.faceRefObjectKey())
                .description(request.description())
                .createdAt(now)
                .updatedAt(now)
                .build());
        mediaAssetService.registerIfAbsent(tenantId, request.faceRefBucket(), request.faceRefObjectKey(), MediaAssetType.CAST_FACE_REFERENCE);
        return toView(profile);
    }

    @Transactional(readOnly = true)
    public List<CastProfileView> list(UUID tenantId, UUID projectId) {
        return castProfileRepository.findByTenantIdAndProjectIdIsNullOrTenantIdAndProjectId(tenantId, tenantId, projectId)
                .stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    CastProfile requireCastProfile(UUID tenantId, UUID castProfileId) {
        return castProfileRepository.findByIdAndTenantId(castProfileId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No cast profile " + castProfileId));
    }

    private CastProfileView toView(CastProfile profile) {
        return new CastProfileView(profile.getId(), profile.getProjectId(), profile.getDisplayName(),
                profile.getFaceRefBucket(), profile.getFaceRefObjectKey(), profile.getDescription());
    }
}
