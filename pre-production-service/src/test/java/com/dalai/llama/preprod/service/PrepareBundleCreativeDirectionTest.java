package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.dto.ApprovedCreativeDirectionContext;
import com.dalai.llama.preprod.dto.PrepareBundleView;
import com.dalai.llama.preprod.service.creativedirection.CreativeDirectionContextService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The shot's video prompt is written from the prepare bundle, so the approved creative direction
 * has to travel in it -- otherwise the video is the one stage made without it.
 */
class PrepareBundleCreativeDirectionTest {

    private final CreativeDirectionContextService creativeDirections = mock(CreativeDirectionContextService.class);
    private final PrepareBundleAssembler assembler = new PrepareBundleAssembler(
            mock(ContinuityBibleService.class), mock(MotionGraphicPlanService.class), mock(ProjectConfigService.class),
            mock(CastAssignmentService.class), mock(CastProfileService.class), mock(ScriptGenerationService.class),
            mock(ShotListGenerationService.class), mock(ShotDialogueBeatService.class), mock(CameraPlanService.class),
            mock(LightingPlanService.class), mock(ShotImageService.class), mock(ShotBackgroundMusicService.class),
            mock(ShotProductReferenceService.class), mock(ShotFoleyCueService.class),
            mock(ShotReferenceImageService.class), creativeDirections);

    private final UUID tenant = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();

    @Test
    void theApprovedDirectionTravelsWithTheBundle() {
        ApprovedCreativeDirectionContext approved = mock(ApprovedCreativeDirectionContext.class);
        when(approved.promptBlock()).thenReturn("APPROVED TREATMENT: warm, handheld");
        when(creativeDirections.findApproved(project)).thenReturn(Optional.of(approved));

        PrepareBundleView bundle = assembler.assemble(tenant, project);

        assertThat(bundle.approvedCreativeDirection()).isEqualTo("APPROVED TREATMENT: warm, handheld");
    }

    @Test
    void withoutAnApprovedDirectionTheBundleSaysSoAndIsNeverRefused() {
        when(creativeDirections.findApproved(project)).thenReturn(Optional.empty());

        PrepareBundleView bundle = assembler.assemble(tenant, project);

        assertThat(bundle.approvedCreativeDirection()).isEqualTo(ApprovedCreativeDirectionContext.NONE_APPROVED);
    }
}
