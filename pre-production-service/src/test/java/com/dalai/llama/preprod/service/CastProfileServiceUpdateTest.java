package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CastProfileType;
import com.dalai.llama.preprod.domain.MediaAssetType;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.UpdateCastProfileRequest;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CastProfileServiceUpdateTest {

    private final UUID tenantId = UUID.randomUUID();
    private final CastProfileRepository castProfileRepository = mock(CastProfileRepository.class);
    private final CastAssignmentRepository castAssignmentRepository = mock(CastAssignmentRepository.class);
    private final MediaAssetService mediaAssetService = mock(MediaAssetService.class);
    private final CreatorVideoEntitlementClient entitlementClient = mock(CreatorVideoEntitlementClient.class);
    private final CastProfileService service = new CastProfileService(
            castProfileRepository, castAssignmentRepository, mock(ProjectRepository.class),
            mediaAssetService, mock(MinioClient.class), entitlementClient);

    {
        given(castProfileRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(castAssignmentRepository.countDistinctProjectsByCastProfileIdIn(any())).willReturn(List.of());
        doCallRealMethod().when(entitlementClient).require(any(), anyBoolean(), any());
    }

    @Test
    void updatesActorDetailsAndKeepsTheFaceWhenNoNewOneIsSent() {
        CastProfile actor = stored(CastProfileType.ACTOR);

        CastProfileView view = service.update(tenantId, actor.getId(),
                new UpdateCastProfileRequest("  Priya Sharma ", "Warm, mid-30s", 34, "FEMALE", null, null));

        assertThat(view.displayName()).isEqualTo("Priya Sharma");
        assertThat(view.description()).isEqualTo("Warm, mid-30s");
        assertThat(view.age()).isEqualTo(34);
        assertThat(view.gender()).isEqualTo("FEMALE");
        assertThat(view.faceRefObjectKey()).isEqualTo("faces/old.png");
        verify(mediaAssetService, never()).registerIfAbsent(any(), any(), any(), any());
    }

    @Test
    void replacesAnActorFaceWhenEntitled() {
        CastProfile actor = stored(CastProfileType.ACTOR);
        entitled(true);

        CastProfileView view = service.update(tenantId, actor.getId(),
                new UpdateCastProfileRequest("Priya", null, null, null, "cast", "faces/new.png"));

        assertThat(view.faceRefObjectKey()).isEqualTo("faces/new.png");
        verify(mediaAssetService).registerIfAbsent(tenantId, "cast", "faces/new.png", MediaAssetType.CAST_FACE_REFERENCE);
    }

    @Test
    void refusesAnActorFaceUploadWithoutTheEntitlement() {
        CastProfile actor = stored(CastProfileType.ACTOR);
        entitled(false);

        assertThatThrownBy(() -> service.update(tenantId, actor.getId(),
                new UpdateCastProfileRequest("Priya", null, null, null, "cast", "faces/new.png")))
                .isInstanceOf(PreProductionException.class)
                .hasMessageContaining("upgrade");
        assertThat(actor.getFaceRefObjectKey()).isEqualTo("faces/old.png");
    }

    @Test
    void ignoresAgeAndGenderOnAProduct() {
        CastProfile product = stored(CastProfileType.PRODUCT);

        CastProfileView view = service.update(tenantId, product.getId(),
                new UpdateCastProfileRequest("Earbuds Pro", "Matte black", 30, "MALE", null, null));

        assertThat(view.displayName()).isEqualTo("Earbuds Pro");
        assertThat(view.age()).isNull();
        assertThat(view.gender()).isNull();
    }

    @Test
    void rejectsAProfileFromAnotherTenant() {
        UUID castProfileId = UUID.randomUUID();
        given(castProfileRepository.findByIdAndTenantId(castProfileId, tenantId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(tenantId, castProfileId,
                new UpdateCastProfileRequest("Priya", null, null, null, null, null)))
                .isInstanceOf(PreProductionException.class);
    }

    private CastProfile stored(CastProfileType type) {
        CastProfile profile = CastProfile.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .profileType(type)
                .displayName("Old name")
                .faceRefBucket("cast")
                .faceRefObjectKey("faces/old.png")
                .build();
        given(castProfileRepository.findByIdAndTenantId(profile.getId(), tenantId)).willReturn(Optional.of(profile));
        return profile;
    }

    private void entitled(boolean imageUpload) {
        given(entitlementClient.get(tenantId)).willReturn(new CreatorVideoEntitlementClient.Entitlements(
                true, true, true, imageUpload, true, true, true, true));
    }
}
