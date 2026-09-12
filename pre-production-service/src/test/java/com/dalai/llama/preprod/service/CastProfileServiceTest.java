package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CastProfileType;
import com.dalai.llama.preprod.domain.VoiceIdentityType;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.dto.ClonedVoiceIdentityView;
import com.dalai.llama.preprod.dto.PersistClonedVoiceRequest;
import com.dalai.llama.preprod.dto.SelectCastProfileBuiltinVoiceRequest;
import com.dalai.llama.preprod.dto.UpdateCastProfileVoiceCommand;
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

        selectBuiltinVoice(tenantId, castProfileId,
                new SelectCastProfileBuiltinVoiceRequest(castProfileId, null, "provider-voice-42", "elevenlabs"));

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

        service.updateVoice(new UpdateCastProfileVoiceCommand(tenantId, castProfileId, castProfileId, null,
                VoiceIdentityType.HUMAN, "media", "voices/actor.wav", null, null));

        assertThat(profile.getVoiceIdentityType()).isEqualTo(VoiceIdentityType.HUMAN);
        assertThat(profile.getVoiceRefBucket()).isEqualTo("media");
        assertThat(profile.getVoiceRefObjectKey()).isEqualTo("voices/actor.wav");
        assertThat(profile.getClonedVoiceId()).isNull();
        assertThat(profile.getClonedVoiceProviderId()).isNull();
    }
    @Test
    void rejectsPayloadCastIdDifferentFromUrl() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selectBuiltinVoice(
                UUID.randomUUID(), UUID.randomUUID(),
                new SelectCastProfileBuiltinVoiceRequest(UUID.randomUUID(), null, "voice", "elevenlabs")))
                .isInstanceOf(PreProductionException.class).hasMessageContaining("must match");
        org.mockito.Mockito.verifyNoInteractions(castProfileRepository);
    }

    @Test
    void validatesProjectScopeBeforeSavingVoice() {
        UUID tenantId = UUID.randomUUID();
        UUID castId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        CastProfile profile = actorProfile(castId, tenantId);
        profile.setProjectId(projectId);
        given(castProfileRepository.findByIdAndTenantId(castId, tenantId)).willReturn(Optional.of(profile));
        given(projectRepository.findByIdAndTenantId(projectId, tenantId))
                .willReturn(Optional.of(mock(com.dalai.llama.preprod.domain.entity.Project.class)));
        given(castProfileRepository.save(profile)).willReturn(profile);
        selectBuiltinVoice(tenantId, castId,
                new SelectCastProfileBuiltinVoiceRequest(castId, projectId, "voice", "elevenlabs"));
        assertThat(profile.getClonedVoiceId()).isEqualTo("voice");
        assertThat(profile.getClonedVoiceProviderId()).isEqualTo("elevenlabs");
        org.mockito.Mockito.verify(projectRepository).findByIdAndTenantId(projectId, tenantId);
        org.mockito.Mockito.verify(castProfileRepository).save(profile);
    }

    @Test
    void rejectsMissingOrDifferentProjectWithoutSaving() {
        UUID tenantId = UUID.randomUUID();
        UUID castId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        CastProfile profile = actorProfile(castId, tenantId);
        profile.setProjectId(UUID.randomUUID());
        given(castProfileRepository.findByIdAndTenantId(castId, tenantId)).willReturn(Optional.of(profile));
        given(projectRepository.findByIdAndTenantId(otherProject, tenantId))
                .willReturn(Optional.of(mock(com.dalai.llama.preprod.domain.entity.Project.class)));
        for (UUID projectId : new UUID[]{null, otherProject}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> selectBuiltinVoice(tenantId, castId,
                    new SelectCastProfileBuiltinVoiceRequest(castId, projectId, "voice", "elevenlabs")))
                    .isInstanceOf(PreProductionException.class);
        }
        org.mockito.Mockito.verify(castProfileRepository, org.mockito.Mockito.never()).save(any());
        assertThat(profile.getClonedVoiceId()).isNull();
    }

    @Test
    void rejectsCastOrProjectOutsideTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID castId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        var request = new SelectCastProfileBuiltinVoiceRequest(castId, projectId, "voice", "elevenlabs");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selectBuiltinVoice(tenantId, castId, request))
                .isInstanceOf(PreProductionException.class).hasMessageContaining("No cast profile");
        given(castProfileRepository.findByIdAndTenantId(castId, tenantId))
                .willReturn(Optional.of(actorProfile(castId, tenantId)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> selectBuiltinVoice(tenantId, castId, request))
                .isInstanceOf(PreProductionException.class).hasMessageContaining("No project");
        org.mockito.Mockito.verify(castProfileRepository, org.mockito.Mockito.never()).save(any());
    }

    private void selectBuiltinVoice(UUID tenantId, UUID castId, SelectCastProfileBuiltinVoiceRequest request) {
        service.updateVoice(new UpdateCastProfileVoiceCommand(
                tenantId, castId, request.castProfileId(), request.projectId(),
                VoiceIdentityType.AI, null, null,
                request.clonedVoiceId(), request.providerId()));
    }

    @Test
    void switchingHumanToAiClearsStoredSample() {
        UUID tenantId = UUID.randomUUID();
        UUID castId = UUID.randomUUID();
        CastProfile profile = actorProfile(castId, tenantId);
        profile.setVoiceRefBucket("media");
        profile.setVoiceRefObjectKey("sample.wav");
        profile.setVoiceIdentityType(VoiceIdentityType.HUMAN);
        given(castProfileRepository.findByIdAndTenantId(castId, tenantId)).willReturn(Optional.of(profile));
        given(castProfileRepository.save(profile)).willReturn(profile);
        service.updateVoice(new UpdateCastProfileVoiceCommand(tenantId, castId, castId, null,
                VoiceIdentityType.AI, null, null, "selected-voice", "elevenlabs"));
        assertThat(profile.getVoiceRefBucket()).isNull();
        assertThat(profile.getVoiceRefObjectKey()).isNull();
        assertThat(profile.getVoiceIdentityType()).isEqualTo(VoiceIdentityType.AI);
        assertThat(profile.getClonedVoiceId()).isEqualTo("selected-voice");
        assertThat(profile.getClonedVoiceProviderId()).isEqualTo("elevenlabs");
    }

    @Test
    void rejectsMissingTypeMissingFieldsAndMixedIdentities() {
        UUID tenantId = UUID.randomUUID();
        UUID castId = UUID.randomUUID();
        for (UpdateCastProfileVoiceCommand command : java.util.List.of(
                new UpdateCastProfileVoiceCommand(tenantId, castId, castId, null, null, null, null, null, null),
                new UpdateCastProfileVoiceCommand(tenantId, castId, castId, null, VoiceIdentityType.HUMAN, "media", null, null, null),
                new UpdateCastProfileVoiceCommand(tenantId, castId, castId, null, VoiceIdentityType.AI, null, null, "voice", null),
                new UpdateCastProfileVoiceCommand(tenantId, castId, castId, null, VoiceIdentityType.HUMAN, "media", "sample.wav", "voice", "elevenlabs"),
                new UpdateCastProfileVoiceCommand(tenantId, castId, castId, null, VoiceIdentityType.AI, "media", "sample.wav", "voice", "elevenlabs"))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateVoice(command))
                    .isInstanceOf(PreProductionException.class);
        }
        org.mockito.Mockito.verifyNoInteractions(castProfileRepository);
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
