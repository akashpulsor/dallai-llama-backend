package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.CameraPlan;
import com.dalai.llama.preprod.domain.entity.LightingPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.dto.ShotAssetCompletionView;
import com.dalai.llama.preprod.repository.CameraPlanRepository;
import com.dalai.llama.preprod.repository.LightingPlanRepository;
import com.dalai.llama.preprod.repository.ShotAssetBatchJobRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** The shot list's two checks: every asset done, and the final production frame present. */
class ShotAssetCompletionTest {

    private final UUID projectId = UUID.randomUUID();
    private final ShotRepository shotRepository = mock(ShotRepository.class);
    private final LightingPlanRepository lightingPlanRepository = mock(LightingPlanRepository.class);
    private final CameraPlanRepository cameraPlanRepository = mock(CameraPlanRepository.class);
    private final ShotImageRepository shotImageRepository = mock(ShotImageRepository.class);
    private final ShotAssetBatchService service = new ShotAssetBatchService(mock(ShotAssetBatchJobRepository.class),
            mock(ShotAssetBatchExecutor.class), shotRepository, lightingPlanRepository, cameraPlanRepository, shotImageRepository);

    @Test
    void aLiveActionShotWithBothPlansAndItsFourImagesIsCompleteWithoutAMotionGraphic() {
        Shot shot = shot(ShotType.ACTION, true,
                ShotImageKind.STORYBOARD, ShotImageKind.PRODUCTION, ShotImageKind.LIGHTING, ShotImageKind.CAMERA_PLAN);
        given(shotRepository.findByProjectIdOrderByShotNumberAsc(projectId)).willReturn(List.of(shot));

        assertThat(service.completion(UUID.randomUUID(), projectId))
                .containsExactly(new ShotAssetCompletionView(shot.getId(), true, true));
    }

    @Test
    void theProductionFrameIsMarkedOnItsOwnEvenWhenOtherAssetsAreMissing() {
        Shot framed = shot(ShotType.ACTION, false, ShotImageKind.PRODUCTION);
        Shot sketchOnly = shot(ShotType.B_ROLL, false, ShotImageKind.STORYBOARD);
        given(shotRepository.findByProjectIdOrderByShotNumberAsc(projectId)).willReturn(List.of(framed, sketchOnly));

        assertThat(service.completion(UUID.randomUUID(), projectId)).containsExactly(
                new ShotAssetCompletionView(framed.getId(), false, true),
                new ShotAssetCompletionView(sketchOnly.getId(), false, false));
    }

    @Test
    void aMotionGraphicShotIsFramedAndDoneOnceItsGraphicExists() {
        Shot withGraphic = shot(ShotType.MOTION_GRAPHIC, false, ShotImageKind.MOTION_GRAPHIC);
        Shot withoutGraphic = shot(ShotType.MOTION_GRAPHIC, false);
        given(shotRepository.findByProjectIdOrderByShotNumberAsc(projectId)).willReturn(List.of(withGraphic, withoutGraphic));

        assertThat(service.completion(UUID.randomUUID(), projectId)).containsExactly(
                new ShotAssetCompletionView(withGraphic.getId(), true, true),
                new ShotAssetCompletionView(withoutGraphic.getId(), false, false));
    }

    @Test
    void aClientFootageShotIsDoneWithoutAnyGeneratedAssetAndHasNoGeneratedFrame() {
        Shot testimonial = shot(ShotType.ACTION, false);
        testimonial.setClientFootage(true);
        given(shotRepository.findByProjectIdOrderByShotNumberAsc(projectId)).willReturn(List.of(testimonial));

        assertThat(service.completion(UUID.randomUUID(), projectId))
                .containsExactly(new ShotAssetCompletionView(testimonial.getId(), true, false));
    }

    @Test
    void theBatchPlansNothingForAClientFootageShot() {
        Shot generated = shot(ShotType.ACTION, false);
        Shot testimonial = shot(ShotType.ACTION, false);
        testimonial.setClientFootage(true);
        given(shotRepository.findByProjectIdOrderByShotNumberAsc(projectId)).willReturn(List.of(generated, testimonial));
        ShotAssetBatchExecutor executor = new ShotAssetBatchExecutor(shotRepository, mock(LightingPlanService.class),
                mock(CameraPlanService.class), mock(ShotImageService.class));

        assertThat(executor.planSteps(UUID.randomUUID(), projectId))
                .isNotEmpty()
                .allSatisfy(step -> assertThat(step.shotId()).isEqualTo(generated.getId()));
    }

    private Shot shot(ShotType type, boolean plans, ShotImageKind... kinds) {
        Shot shot = Shot.builder().id(UUID.randomUUID()).projectId(projectId).shotNumber(1).shotType(type).build();
        given(lightingPlanRepository.findByShotId(shot.getId())).willReturn(plans ? Optional.of(new LightingPlan()) : Optional.empty());
        given(cameraPlanRepository.findByShotId(shot.getId())).willReturn(plans ? Optional.of(new CameraPlan()) : Optional.empty());
        given(shotImageRepository.findByShotId(shot.getId())).willReturn(Arrays.stream(kinds)
                .map(kind -> ShotImage.builder().shotId(shot.getId()).kind(kind).build()).toList());
        return shot;
    }
}
