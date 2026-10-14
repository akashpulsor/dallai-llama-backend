package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.MotionGraphicPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.MotionGraphicPlanRequests;
import com.dalai.llama.preprod.dto.MotionGraphicPlanView;
import com.dalai.llama.preprod.repository.MotionGraphicPlanRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.creativedirection.CreativeDirectionContextService;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A motion graphic's plan drives its shot, so the creator owns it: they can write it themselves, or
 * ask for it to be improved and keep the suggestion only if they like it.
 */
class MotionGraphicPlanEditTest {

    private final ShotRepository shots = mock(ShotRepository.class);
    private final MotionGraphicPlanRepository plans = mock(MotionGraphicPlanRepository.class);
    private final LlmGatewayClient gateway = mock(LlmGatewayClient.class);
    private final CreativeDirectionContextService direction = mock(CreativeDirectionContextService.class);
    private final MotionGraphicPlanService service =
            new MotionGraphicPlanService(shots, plans, gateway, new ObjectMapper(), "gemini-text", direction);

    private final UUID tenant = UUID.randomUUID();
    private final UUID shotId = UUID.randomUUID();
    private final Shot graphic = Shot.builder().id(shotId).projectId(UUID.randomUUID())
            .shotType(ShotType.MOTION_GRAPHIC).durationSeconds(4).action("App reveal").build();
    private final MotionGraphicPlan current = MotionGraphicPlan.builder().id(UUID.randomUUID()).tenantId(tenant).shotId(shotId)
            .concept("App reveal").onScreenText("Your stars, decoded.").visualStyle("Flat neon")
            .animationNotes("The logo fades in. The tagline appears.").durationSeconds(4).build();

    {
        when(shots.findByIdAndTenantId(shotId, tenant)).thenReturn(Optional.of(graphic));
        when(plans.save(any())).thenAnswer(call -> call.getArgument(0));
        when(direction.promptBlock(any(), any())).thenReturn("No creative direction has been approved.");
    }

    @Test
    void anEditSavesThePlanExactlyAsWrittenAndBlankClearsAField() {
        when(plans.findByShotId(shotId)).thenReturn(Optional.of(current));

        MotionGraphicPlanView saved = service.update(tenant, shotId, new MotionGraphicPlanRequests.Edit(
                "App reveal", " Your stars, decoded. ", "", "The logo fades in at centre. The tagline wipes in from the left."));

        assertThat(saved.onScreenText()).isEqualTo("Your stars, decoded.");
        assertThat(saved.visualStyle()).isNull();
        assertThat(saved.animationNotes()).isEqualTo("The logo fades in at centre. The tagline wipes in from the left.");
        verify(plans).save(current);
    }

    @Test
    void anEnhanceSendsTheCurrentPlanAndTheRequestAndSavesNothing() {
        when(plans.findByShotId(shotId)).thenReturn(Optional.of(current));
        when(gateway.chat(anyString(), anyString(), any())).thenReturn(new LlmGatewayChatResponse(UUID.randomUUID(), "gemini-text",
                "{\"concept\":\"App reveal\",\"onScreenText\":\"Your stars, decoded.\",\"visualStyle\":\"Bold neon\","
                        + "\"animationNotes\":\"The logo snaps in. The tagline lands last.\",\"durationSeconds\":4}",
                null, 10, "STOP"));

        MotionGraphicPlanView suggestion = service.enhance(tenant, shotId, "make the tagline land last");

        ArgumentCaptor<LlmGatewayChatRequest> request = ArgumentCaptor.forClass(LlmGatewayChatRequest.class);
        verify(gateway).chat(anyString(), anyString(), request.capture());
        assertThat(request.getValue().taskKey()).isEqualTo("PRE_PROD_MOTION_GRAPHIC_PLAN_ENHANCE");
        assertThat(request.getValue().templateVariables())
                .containsEntry("instruction", "make the tagline land last")
                .containsEntry("animationNotes", "The logo fades in. The tagline appears.")
                .containsEntry("onScreenText", "Your stars, decoded.");
        assertThat(suggestion.animationNotes()).isEqualTo("The logo snaps in. The tagline lands last.");
        // A suggestion: the creator keeps it with an edit, or not at all.
        verify(plans, never()).save(any());
        assertThat(current.getAnimationNotes()).isEqualTo("The logo fades in. The tagline appears.");
    }

    @Test
    void onlyAMotionGraphicHasAPlanToEdit() {
        UUID other = UUID.randomUUID();
        when(shots.findByIdAndTenantId(other, tenant)).thenReturn(Optional.of(
                Shot.builder().id(other).shotType(ShotType.DIALOGUE).build()));

        assertThatThrownBy(() -> service.update(tenant, other, new MotionGraphicPlanRequests.Edit("x", null, null, null)))
                .hasMessageContaining("not a MOTION_GRAPHIC shot");
    }
}
