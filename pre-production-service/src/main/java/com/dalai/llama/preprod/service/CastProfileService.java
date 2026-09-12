package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CastProfileType;
import com.dalai.llama.preprod.domain.MediaAssetType;
import com.dalai.llama.preprod.domain.VoiceIdentityType;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.ClonedVoiceIdentityView;
import com.dalai.llama.preprod.dto.PersistClonedVoiceRequest;
import com.dalai.llama.preprod.dto.CreateCastProfileRequest;
import com.dalai.llama.preprod.dto.SelectCastProfileBuiltinVoiceCommand;
import com.dalai.llama.preprod.dto.UpdateCastProfileVoiceCommand;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
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
    private final ProjectRepository projectRepository;
    private final MediaAssetService mediaAssetService;
    private final MinioClient publicMinioClient;

    public CastProfileService(
            CastProfileRepository castProfileRepository,
            CastAssignmentRepository castAssignmentRepository,
            ProjectRepository projectRepository,
            MediaAssetService mediaAssetService,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient
    ) {
        this.castProfileRepository = castProfileRepository;
        this.castAssignmentRepository = castAssignmentRepository;
        this.projectRepository = projectRepository;
        this.mediaAssetService = mediaAssetService;
        this.publicMinioClient = publicMinioClient;
    }

    @Transactional
    public CastProfileView create(UUID tenantId, CreateCastProfileRequest request) {
        CastProfileType profileType = request.profileType() == null ? CastProfileType.ACTOR : request.profileType();
        VoiceIdentity identity = initialVoiceIdentity(profileType, request);
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
                // Retained as a compatibility mirror for older readers; new code uses the provider pair below.
                .builtinVoiceId(profileType == CastProfileType.ACTOR ? identity.legacyBuiltinVoiceId() : null)
                .clonedVoiceId(profileType == CastProfileType.ACTOR ? identity.voiceId() : null)
                .clonedVoiceProviderId(profileType == CastProfileType.ACTOR ? identity.providerId() : null)
                .voiceIdentityType(profileType == CastProfileType.ACTOR ? identity.type() : null)
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

    /** Selects a HUMAN uploaded sample or an AI provider identity, clearing the other representation. */
    @Transactional
    public CastProfileView updateVoice(UpdateCastProfileVoiceCommand request) {
        UUID tenantId = request.tenantId();
        UUID castProfileId = request.routeCastProfileId();
        if (tenantId == null || castProfileId == null || !castProfileId.equals(request.castProfileId())) {
            throw PreProductionException.badRequest("Tenant is required and castProfileId must match the URL cast profile ID");
        }
        if (request.voiceIdentityType() == null) {
            throw PreProductionException.badRequest("voiceIdentityType is required");
        }
        boolean human = request.voiceIdentityType() == VoiceIdentityType.HUMAN;
        if (human && (blank(request.voiceRefBucket()) || blank(request.voiceRefObjectKey()))) {
            throw PreProductionException.badRequest("HUMAN voice requires voiceRefBucket and voiceRefObjectKey");
        }
        if (!human && (blank(request.clonedVoiceId()) || blank(request.providerId()))) {
            throw PreProductionException.badRequest("AI voice requires clonedVoiceId and providerId");
        }
        if (human && (!blank(request.clonedVoiceId()) || !blank(request.providerId()))
                || !human && (!blank(request.voiceRefBucket()) || !blank(request.voiceRefObjectKey()))) {
            throw PreProductionException.badRequest("Voice sample fields and provider identity fields are mutually exclusive");
        }
        CastProfile profile = requireCastProfile(tenantId, castProfileId);
        if (request.projectId() != null) {
            requireProjectScope(tenantId, request.projectId(), profile);
        } else if (profile.getProjectId() != null) {
            throw PreProductionException.badRequest("projectId is required for a project cast profile");
        }
        requireActorProfile(profile);
        profile.setVoiceRefBucket(human ? request.voiceRefBucket() : null);
        profile.setVoiceRefObjectKey(human ? request.voiceRefObjectKey() : null);
        profile.setBuiltinVoiceId(human ? null : request.clonedVoiceId());
        profile.setClonedVoiceId(human ? null : request.clonedVoiceId());
        profile.setClonedVoiceProviderId(human ? null : request.providerId());
        profile.setVoiceIdentityType(request.voiceIdentityType());
        profile.setUpdatedAt(OffsetDateTime.now());
        CastProfile saved = castProfileRepository.save(profile);
        if (human) {
            mediaAssetService.registerIfAbsent(tenantId, request.voiceRefBucket(), request.voiceRefObjectKey(), MediaAssetType.CAST_VOICE_REFERENCE);
        }
        return toView(saved);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Alternative to {@link #updateVoice} for a character with no recorded sample to clone: picks
     * a stock ElevenLabs voice (see llm-gateway's {@code builtin_voice} table) instead. Clears any
     * previously-uploaded sample -- the two are alternative choices, not additive. */
    @Transactional
    public CastProfileView selectBuiltinVoice(SelectCastProfileBuiltinVoiceCommand request) {
        return updateVoice(new UpdateCastProfileVoiceCommand(
                request.tenantId(), request.routeCastProfileId(), request.castProfileId(), request.projectId(),
                VoiceIdentityType.AI, null, null, request.clonedVoiceId(), request.providerId()));
    }
    /**
     * Stores a provider clone identity once, without letting retries or concurrent requests
     * replace the first identity. The response always describes the identity that is now durable.
     */
    @Transactional
    public ClonedVoiceIdentityView persistClonedVoiceIfAbsent(
            UUID tenantId, UUID projectId, UUID castProfileId, PersistClonedVoiceRequest request) {
        CastProfile profile = requireCastProfile(tenantId, castProfileId);
        requireProjectScope(tenantId, projectId, profile);
        requireActorProfile(profile);

        int claimed = castProfileRepository.persistClonedVoiceIfAbsent(
                tenantId, castProfileId, request.clonedVoiceId(), request.providerId(),
                request.voiceIdentityType(), OffsetDateTime.now());
        if (claimed == 1) {
            return new ClonedVoiceIdentityView(
                    request.clonedVoiceId(), request.providerId(), request.voiceIdentityType(), true);
        }

        CastProfile existing = requireCastProfile(tenantId, castProfileId);
        if (hasClonedVoiceIdentity(existing)) {
            return new ClonedVoiceIdentityView(
                    existing.getClonedVoiceId(), existing.getClonedVoiceProviderId(),
                    existing.getVoiceIdentityType(), false);
        }
        throw PreProductionException.conflict(
                "Cast profile " + castProfileId + " cannot claim a cloned voice while a built-in voice is selected");
    }

    /** Reusable library profiles are valid for every tenant project; project-local profiles are not. */
    private void requireProjectScope(UUID tenantId, UUID projectId, CastProfile profile) {
        projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No project " + projectId));
        if (profile.getProjectId() != null && !profile.getProjectId().equals(projectId)) {
            throw PreProductionException.notFound("Cast profile " + profile.getId() + " is not available in project " + projectId);
        }
    }

    private boolean hasClonedVoiceIdentity(CastProfile profile) {
        return profile.getClonedVoiceId() != null && !profile.getClonedVoiceId().isBlank()
                && profile.getClonedVoiceProviderId() != null && !profile.getClonedVoiceProviderId().isBlank()
                && profile.getVoiceIdentityType() != null;
    }

    /** Resolves creation input into the one durable voice-identity model. Human samples deliberately
     * have no provider voice id yet: Prepare All Dialogues creates it after the profile exists. */
    private VoiceIdentity initialVoiceIdentity(CastProfileType profileType, CreateCastProfileRequest request) {
        if (profileType != CastProfileType.ACTOR) {
            return VoiceIdentity.NONE;
        }
        if (hasText(request.voiceRefBucket()) && hasText(request.voiceRefObjectKey())) {
            return new VoiceIdentity(null, null, null, VoiceIdentityType.HUMAN);
        }
        boolean hasCloneId = hasText(request.clonedVoiceId());
        boolean hasProviderId = hasText(request.clonedVoiceProviderId());
        if (hasCloneId != hasProviderId) {
            throw PreProductionException.badRequest("clonedVoiceId and clonedVoiceProviderId must be supplied together");
        }
        if (hasCloneId) {
            return new VoiceIdentity(request.clonedVoiceId(), request.clonedVoiceProviderId(), request.clonedVoiceId(), VoiceIdentityType.AI);
        }
        if (hasText(request.builtinVoiceId())) {
            // Pre-provider-id clients only ever selected ElevenLabs voices. New clients must send the pair above.
            return new VoiceIdentity(request.builtinVoiceId(), "elevenlabs", request.builtinVoiceId(), VoiceIdentityType.AI);
        }
        return new VoiceIdentity(null, null, null, null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record VoiceIdentity(String voiceId, String providerId, String legacyBuiltinVoiceId, VoiceIdentityType type) {
        private static final VoiceIdentity NONE = new VoiceIdentity(null, null, null, null);
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
                profile.getBuiltinVoiceId(), profile.getClonedVoiceId(), profile.getClonedVoiceProviderId(),
                profile.getVoiceIdentityType(), projectCount);
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
