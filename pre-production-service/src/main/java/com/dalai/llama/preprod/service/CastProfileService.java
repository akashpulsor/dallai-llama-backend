package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CastProfileType;
import com.dalai.llama.preprod.domain.MediaAssetType;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.CreateCastProfileRequest;
import com.dalai.llama.preprod.dto.SelectCastProfileBuiltinVoiceRequest;
import com.dalai.llama.preprod.dto.UpdateCastProfileVoiceRequest;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class CastProfileService {

    private final CastProfileRepository castProfileRepository;
    private final CastAssignmentRepository castAssignmentRepository;
    private final MediaAssetService mediaAssetService;
    private final MinioClient publicMinioClient;

    public CastProfileService(
            CastProfileRepository castProfileRepository,
            CastAssignmentRepository castAssignmentRepository,
            MediaAssetService mediaAssetService,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient
    ) {
        this.castProfileRepository = castProfileRepository;
        this.castAssignmentRepository = castAssignmentRepository;
        this.mediaAssetService = mediaAssetService;
        this.publicMinioClient = publicMinioClient;
    }

    @Transactional
    public CastProfileView create(UUID tenantId, CreateCastProfileRequest request) {
        CastProfileType profileType = request.profileType() == null ? CastProfileType.ACTOR : request.profileType();
        OffsetDateTime now = OffsetDateTime.now();
        CastProfile profile = castProfileRepository.save(CastProfile.builder()
                .tenantId(tenantId)
                .projectId(request.projectId())
                .profileType(profileType)
                .displayName(request.displayName())
                .faceRefBucket(request.faceRefBucket())
                .faceRefObjectKey(request.faceRefObjectKey())
                .description(request.description())
                .age(profileType == CastProfileType.ACTOR ? request.age() : null)
                .gender(profileType == CastProfileType.ACTOR ? request.gender() : null)
                .voiceRefBucket(profileType == CastProfileType.ACTOR ? request.voiceRefBucket() : null)
                .voiceRefObjectKey(profileType == CastProfileType.ACTOR ? request.voiceRefObjectKey() : null)
                .builtinVoiceId(profileType == CastProfileType.ACTOR ? request.builtinVoiceId() : null)
                .createdAt(now)
                .updatedAt(now)
                .build());
        if (request.faceRefBucket() != null && request.faceRefObjectKey() != null) {
            mediaAssetService.registerIfAbsent(tenantId, request.faceRefBucket(), request.faceRefObjectKey(),
                    profileType == CastProfileType.PRODUCT ? MediaAssetType.PRODUCT_REFERENCE : MediaAssetType.CAST_FACE_REFERENCE);
        }
        if (profile.getVoiceRefBucket() != null && profile.getVoiceRefObjectKey() != null) {
            mediaAssetService.registerIfAbsent(tenantId, profile.getVoiceRefBucket(), profile.getVoiceRefObjectKey(), MediaAssetType.CAST_VOICE_REFERENCE);
        }
        return toView(profile);
    }

    /** The only way a cast profile's voice reference gets set today is at creation time
     * ({@link #create}) -- a profile created before an actor's voice sample was ready (or one
     * shared across characters, like a narrator reusing an on-screen actor's profile) had no way
     * to add or change it afterward. Same upload -> attach pattern as create: the caller already
     * has bucket/objectKey from {@code POST /v1/cast-profiles/media}. ACTOR-only, like every other
     * voice field on this entity -- a PRODUCT/NARRATOR profile has no dialogue to dub. Uploading a
     * real sample supersedes any previously-picked built-in voice. */
    @Transactional
    public CastProfileView updateVoice(UUID tenantId, UUID castProfileId, UpdateCastProfileVoiceRequest request) {
        CastProfile profile = requireCastProfile(tenantId, castProfileId);
        requireActorProfile(profile);
        profile.setVoiceRefBucket(request.voiceRefBucket());
        profile.setVoiceRefObjectKey(request.voiceRefObjectKey());
        profile.setBuiltinVoiceId(null);
        profile.setUpdatedAt(OffsetDateTime.now());
        CastProfile saved = castProfileRepository.save(profile);
        mediaAssetService.registerIfAbsent(tenantId, request.voiceRefBucket(), request.voiceRefObjectKey(), MediaAssetType.CAST_VOICE_REFERENCE);
        return toView(saved);
    }

    /** Alternative to {@link #updateVoice} for a character with no recorded sample to clone: picks
     * a stock ElevenLabs voice (see llm-gateway's {@code builtin_voice} table) instead. Clears any
     * previously-uploaded sample -- the two are alternative choices, not additive. */
    @Transactional
    public CastProfileView selectBuiltinVoice(UUID tenantId, UUID castProfileId, SelectCastProfileBuiltinVoiceRequest request) {
        CastProfile profile = requireCastProfile(tenantId, castProfileId);
        requireActorProfile(profile);
        profile.setBuiltinVoiceId(request.builtinVoiceId());
        profile.setVoiceRefBucket(null);
        profile.setVoiceRefObjectKey(null);
        profile.setUpdatedAt(OffsetDateTime.now());
        return toView(castProfileRepository.save(profile));
    }

    private void requireActorProfile(CastProfile profile) {
        if (profile.getProfileType() != CastProfileType.ACTOR) {
            throw PreProductionException.badRequest(
                    "Cast profile " + profile.getId() + " is a " + profile.getProfileType() + " profile -- voice only applies to ACTOR profiles");
        }
    }

    @Transactional(readOnly = true)
    public List<CastProfileView> list(UUID tenantId, UUID projectId, CastProfileType profileType) {
        List<CastProfile> profiles = castProfileRepository.findByTenantIdAndProjectIdIsNullOrTenantIdAndProjectId(tenantId, tenantId, projectId)
                .stream()
                .filter(p -> profileType == null || p.getProfileType() == profileType)
                .collect(Collectors.toList());
        Map<UUID, Long> projectCounts = castAssignmentRepository
                .countDistinctProjectsByCastProfileIdIn(profiles.stream().map(CastProfile::getId).collect(Collectors.toList())).stream()
                .collect(Collectors.toMap(CastAssignmentRepository.CastProfileProjectCount::getCastProfileId,
                        CastAssignmentRepository.CastProfileProjectCount::getProjectCount));
        return profiles.stream().map(p -> toView(p, projectCounts.getOrDefault(p.getId(), 0L))).collect(Collectors.toList());
    }

    CastProfile requireCastProfile(UUID tenantId, UUID castProfileId) {
        return castProfileRepository.findByIdAndTenantId(castProfileId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No cast profile " + castProfileId));
    }

    private CastProfileView toView(CastProfile profile) {
        long projectCount = castAssignmentRepository.countDistinctProjectsByCastProfileIdIn(List.of(profile.getId())).stream()
                .findFirst().map(CastAssignmentRepository.CastProfileProjectCount::getProjectCount).orElse(0L);
        return toView(profile, projectCount);
    }

    private CastProfileView toView(CastProfile profile, long projectCount) {
        return new CastProfileView(profile.getId(), profile.getProjectId(), profile.getProfileType(), profile.getDisplayName(),
                profile.getFaceRefBucket(), profile.getFaceRefObjectKey(), signedUrl(profile.getFaceRefBucket(), profile.getFaceRefObjectKey()),
                profile.getDescription(), profile.getAge(), profile.getGender(), profile.getVoiceRefBucket(), profile.getVoiceRefObjectKey(),
                profile.getBuiltinVoiceId(), projectCount);
    }

    /** Display-only, so any presign failure degrades to no photo rather than a broken cast list. */
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
}
