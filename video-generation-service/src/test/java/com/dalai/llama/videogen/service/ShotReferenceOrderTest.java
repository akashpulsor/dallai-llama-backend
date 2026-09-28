package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.domain.ReferenceKind;
import com.dalai.llama.videogen.dto.shotcontext.Camera;
import com.dalai.llama.videogen.dto.shotcontext.Character;
import com.dalai.llama.videogen.dto.shotcontext.Lighting;
import com.dalai.llama.videogen.dto.shotcontext.ProductBrand;
import com.dalai.llama.videogen.dto.shotcontext.ReferenceFrame;
import com.dalai.llama.videogen.dto.shotcontext.ShotContext;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The numbers the prompt gives the model must be the order the provider receives the images in.
 *
 * <p>They were not. {@code saveReferences} puts the shot's own frame in slot 0 and the cast after
 * it; the description numbered the cast from 1 and put the frame last. So a prompt said "reference
 * image 1 = Neha" while image 1 was the storyboard, and the written prompt duly described the
 * frame as though it were the person.
 *
 * <p>This pins the description against {@code saveReferences}' documented sequence: frame, cast,
 * product, lighting, camera plan, creator bundle -- image kinds only, since CHARACTER_VOICE and
 * BACKGROUND_MUSIC take a slot but are not images and {@code resolveReferenceImageUrls} filters
 * them out. If that sequence changes, this test fails and the description has to change with it.
 */
class ShotReferenceOrderTest {

    private final LlmGatewayClient gateway = mock(LlmGatewayClient.class);

    /** The order ShotGenerationOrchestrator.saveReferences appends IMAGE references in. */
    private static final List<ReferenceKind> PROVIDER_IMAGE_ORDER = List.of(
            ReferenceKind.STORYBOARD,
            ReferenceKind.CHARACTER_FACE,
            ReferenceKind.PRODUCT_HERO,
            ReferenceKind.DP_LIGHTING,
            ReferenceKind.CAMERA_PLAN_IMAGE,
            ReferenceKind.SHOT_REFERENCE);

    @Test
    void everyKindIsNumberedInTheOrderTheProviderReceivesIt() {
        ShotContext context = mock(ShotContext.class);
        when(context.shotRef()).thenReturn("shot-01-002");
        when(context.sceneType()).thenReturn("LIVE_ACTION");
        when(context.technical()).thenReturn(null);
        when(context.referenceFrames()).thenReturn(List.of(
                new ReferenceFrame(ReferenceKind.STORYBOARD, "b", "frames/shot.png")));
        when(context.characters()).thenReturn(List.of(new Character(
                UUID.randomUUID().toString(), "b", "faces/neha.png", "grey kurta", "calm",
                "b", "voice/neha.mp3", "Neha", "38")));
        ProductBrand product = mock(ProductBrand.class);
        when(product.productRefObjectKey()).thenReturn("product/hero.png");
        when(context.productBrand()).thenReturn(product);
        Lighting lighting = mock(Lighting.class);
        when(lighting.dpLightingImageObjectKey()).thenReturn("lighting/plan.png");
        when(context.lighting()).thenReturn(lighting);
        Camera camera = mock(Camera.class);
        when(camera.cameraPlanImageObjectKey()).thenReturn("camera/plan.png");
        when(context.camera()).thenReturn(camera);
        when(context.referenceImages()).thenReturn(List.of(
                new ShotContext.ShotReferenceImage("b", "refs/home.png", "image/png", null, "app home screen", 0)));

        when(gateway.chat(anyString(), anyString(), any(LlmGatewayChatRequest.class)))
                .thenReturn(new LlmGatewayChatResponse(UUID.randomUUID(), "m", "a prompt", null, 1L));

        new VideoShotPromptService(gateway, new ObjectMapper(), "m", true)
                .writePrompt(UUID.randomUUID(), UUID.randomUUID(), context, "the plan", 20000);

        ArgumentCaptor<LlmGatewayChatRequest> sent = ArgumentCaptor.forClass(LlmGatewayChatRequest.class);
        verify(gateway).chat(anyString(), anyString(), sent.capture());
        String references = sent.getValue().templateVariables().get("references");

        // One line per image kind, numbered 1..6 with nothing skipped or repeated.
        assertThat(references.lines().count()).isEqualTo(PROVIDER_IMAGE_ORDER.size());
        assertThat(references).contains("reference image 1 = this shot's own frame");
        assertThat(references).contains("reference image 2 = Neha");
        assertThat(references).contains("reference image 3 = the product");
        assertThat(references).contains("reference image 4 = the lighting reference");
        assertThat(references).contains("reference image 5 = the camera-plan frame");
        assertThat(references).contains("reference image 6 = \"app home screen\"");
    }

    @Test
    void theFrameIsToldItLosesToThePlanWhenTheyDisagree() {
        // The frame is an earlier artefact than the written plan. A model handed both and no rule
        // will follow the picture, which is how a hand entering "from the lower edge" in the plan
        // came back entering "from screen right".
        ShotContext context = mock(ShotContext.class);
        when(context.shotRef()).thenReturn("shot-01-002");
        when(context.sceneType()).thenReturn("LIVE_ACTION");
        when(context.technical()).thenReturn(null);
        when(context.referenceFrames()).thenReturn(List.of(
                new ReferenceFrame(ReferenceKind.STORYBOARD, "b", "frames/shot.png")));
        when(gateway.chat(anyString(), anyString(), any(LlmGatewayChatRequest.class)))
                .thenReturn(new LlmGatewayChatResponse(UUID.randomUUID(), "m", "a prompt", null, 1L));

        new VideoShotPromptService(gateway, new ObjectMapper(), "m", true)
                .writePrompt(UUID.randomUUID(), UUID.randomUUID(), context, "the plan", 20000);

        ArgumentCaptor<LlmGatewayChatRequest> sent = ArgumentCaptor.forClass(LlmGatewayChatRequest.class);
        verify(gateway).chat(anyString(), anyString(), sent.capture());
        assertThat(sent.getValue().templateVariables().get("references"))
                .contains("the PLAN wins");
    }
}
