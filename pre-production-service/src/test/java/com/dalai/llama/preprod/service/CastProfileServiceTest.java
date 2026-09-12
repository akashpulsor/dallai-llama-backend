package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CastProfileType;
import com.dalai.llama.preprod.domain.VoiceIdentityType;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.dto.ClonedVoiceIdentityView;
import com.dalai.llama.preprod.dto.PersistClonedVoiceRequest;
import com.dalai.llama.preprod.dto.SelectCastProfileBuiltinVoiceRequest;
import com.dalai.llama.preprod.dto.UpdateCastProfileVoiceRequest;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CastProfileServiceTest {

    private final CastProfileRepository castProfileRepository = mock(CastProfileRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final CastProfileService service = new CastProfileService(
            castProfileRepository,
            mock(CastAssignmentRepository.class),
            projectRepository,
            mock(MediaAssetService.class),
            mock(MinioClient.class));

    @Test
    void persistsTheFirstHumanCloneIdentity() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID castProfileId = UUID.randomUUID();
        CastProfile profile = actorProfile(castProfileId, tenantId);
        given(projectRepository.findByIdAndTenantId(projectId, tenantId)).willReturn(Optional.of(mock(com.dalai.llama.preprod.domain.entity.Project.class)));
        PersistClonedVoiceRequest request = new PersistClonedVoiceRequest(
                "eleven-clone-1", "elevenlabs", VoiceIdentityType.HUMAN);
        given(castProfileRepository.findByIdAndTenantId(castProfileId, tenantId)).willReturn(Optional.of(profile));
        given(castProfileRepository.persistClonedVoiceIfAbsent(
                eq(tenantId), eq(castProfileId), eq("eleven-clone-1"), eq("elevenlabs"),
                eq(VoiceIdentityType.HUMAN), any())).willReturn(1);

        ClonedVoiceIdentityView result = service.persistClonedVoiceIfAbsent(tenantId, projectId, castProfileId, request);

        assertThat(result).isEqualTo(new ClonedVoiceIdentityView(
                "eleven-clone-1", "elevenlabs", VoiceIdentityType.HUMAN, true));
    }

    @Test
    void returnsTheExistingCloneWhenAnotherRequestAlreadyPersistedIt() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID castProfileId = UUID.randomUUID();
        CastProfile beforeClaim = actorProfile(castProfileId, tenantId);
        given(projectRepository.findByIdAndTenantId(projectId, tenantId)).willReturn(Optional.of(mock(com.dalai.llama.preprod.domain.entity.Project.class)));
        CastProfile stored = actorProfile(castProfileId, tenantId);
        stored.setClonedVoiceId("first-clone");
        stored.setClonedVoiceProviderId("elevenlabs");
        stored.setVoiceIdentityType(VoiceIdentityType.HUMAN);
        PersistClonedVoiceRequest request = new PersistClonedVoiceRequest(
                "later-clone", "elevenlabs", VoiceIdentityType.HUMAN);
        given(castProfileRepository.findByIdAndTenantId(castProfileId, tenantId))
                .willReturn(Optional.of(beforeClaim), Optional.of(stored));
        given(castProfileRepository.persistClonedVoiceIfAbsent(
                eq(tenantId), eq(castProfileId), eq("later-clone"), eq("elevenlabs"),
                eq(VoiceIdentityType.HUMAN), any())).willReturn(0);

        ClonedVoiceIdentityView result = service.persistClonedVoiceIfAbsent(tenantId, projectId, castProfileId, request);

        assertThat(result).isEqualTo(new ClonedVoiceIdentityView(
                "first-clone", "elevenlabs", VoiceIdentityType.HUMAN, false));
    }

    @Test
    void savesTheProviderQualifiedAiVoiceSelectedFromTheCatalog() {
        UUID tenantId = UUID.randomUUID();
        UUID castProfileId = UUID.randomUUID();
        CastProfile profile = actorProfile(castProfileId, tenantId);
        given(castProfileRepository.findByIdAndTenantId(castProfileId, tenantId)).willReturn(Optional.of(profile));
        given(castProfileRepository.save(profile)).willReturn(profile);

        service.selectBuiltinVoice(tenantId, castProfileId,
                new SelectCastProfileBuiltinVoiceRequest("provider-voice-42", "elevenlabs"));

        assertThat(profile.getVoiceIdentityType()).isEqualTo(VoiceIdentityType.AI);
        assertThat(profile.getClonedVoiceId()).isEqualTo("provider-voice-42");
        assertThat(profile.getClonedVoiceProviderId()).isEqualTo("elevenlabs");
        assertThat(profile.getBuiltinVoiceId()).isEqualTo("provider-voice-42");
        assertThat(profile.getVoiceRefBucket()).isNull();
    }

    @Test
    void recordsHumanSampleWithoutPrematurelyCreatingAProviderVoice() {
        UUID tenantId = UUID.randomUUID();
        UUID castProfileId = UUID.randomUUID();
        CastProfile profile = actorProfile(castProfileId, tenantId);
        profile.setClonedVoiceId("old-provider-voice");
        profile.setClonedVoiceProviderId("elevenlabs");
        profile.setBuiltinVoiceId("old-provider-voice");
        given(castProfileRepository.findByIdAndTenantId(castProfileId, tenantId)).willReturn(Optional.of(profile));
        given(castProfileRepository.save(profile)).willReturn(profile);

        service.updateVoice(tenantId, castProfileId, new UpdateCastProfileVoiceRequest("media", "voices/actor.wav"));

        assertThat(profile.getVoiceIdentityType()).isEqualTo(VoiceIdentityType.HUMAN);
        assertThat(profile.getVoiceRefBucket()).isEqualTo("media");
        assertThat(profile.getVoiceRefObjectKey()).isEqualTo("voices/actor.wav");
        assertThat(profile.getClonedVoiceId()).isNull();
        assertThat(profile.getClonedVoiceProviderId()).isNull();
    }
    private static CastProfile actorProfile(UUID castProfileId, UUID tenantId) {
        return CastProfile.builder()
                .id(castProfileId)
                .tenantId(tenantId)
                .profileType(CastProfileType.ACTOR)
                .displayName("Actor")
                .build();
    }
}