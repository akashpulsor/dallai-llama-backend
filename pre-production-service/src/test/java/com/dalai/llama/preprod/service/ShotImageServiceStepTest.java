package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.repository.CameraPlanRepository;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.LightingPlanRepository;
import com.dalai.llama.preprod.repository.MotionGraphicPlanRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.repository.ShotProductReferenceRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.continuity.ContinuityResolution;
import com.dalai.llama.preprod.service.continuity.StepContinuityService;
import com.dalai.llama.preprod.service.creativedirection.CreativeDirectionContextService;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ShotImageServiceStepTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final ShotRepository shotRepository = mock(ShotRepository.class);
    private final ShotImageRepository shotImageRepository = mock(ShotImageRepository.class);
    private final MinioClient minioClient = mock(MinioClient.class);
    private final LlmGatewayClient llmGatewayClient = mock(LlmGatewayClient.class);
    private final StepContinuityService stepContinuity = mock(StepContinuityService.class);
    private final ShotImageService service = new ShotImageService(
            shotRepository, mock(ScriptRepository.class), mock(ScriptCharacterRepository.class),
            mock(ScreenplaySceneCharacterRepository.class), mock(CastAssignmentRepository.class),
            mock(CastProfileRepository.class), shotImageRepository, mock(ShotProductReferenceRepository.class),
            mock(LightingPlanRepository.class), mock(CameraPlanRepository.class), mock(MotionGraphicPlanRepository.class),
            llmGatewayClient, mock(MediaAssetService.class), mock(GenerationThoughtService.class),
            mock(ShotImageDescriptionService.class), minioClient, mock(MinioClient.class), new ObjectMapper(),
            "storyboards", "shots", "gemini-image", "gemini-image", "flux-schnell", "gemini-image-lite", "gemini-text",
            mock(CreativeDirectionContextService.class), stepContinuity);

    {
        // Continuity has its own tests (ContinuityResolverTest, StepContinuityEndToEndTest); here a
        // step with nothing resolved keeps the generic step instruction.
        given(stepContinuity.resolve(any(), any(), any(), any(), any(), any(), any())).willReturn(ContinuityResolution.NONE);
    }

    @Test
    void stepBundleZipsTheStepPromptAndTheEarlierShotsImageWithoutGenerating() throws Exception {
        Shot source = shot(1, "Priya opens the door");
        Shot target = shot(2, "Priya steps into the hallway");
        ShotImage sourceImage = image(source, ShotImageKind.STORYBOARD, "shots/1.png");
        given(shotImageRepository.findByShotIdAndKind(source.getId(), ShotImageKind.STORYBOARD)).willReturn(Optional.of(sourceImage));
        storedPng("shots/1.png");

        ShotImageBundle bundle = service.stepBundle(tenantId, target.getId(), ShotImageKind.STORYBOARD, source.getId(), "only Priya stays");

        assertThat(bundle.fileName()).isEqualTo("shot-2-step-from-1-storyboard.zip");
        Map<String, byte[]> entries = unzip(bundle.zip());
        assertThat(entries.keySet()).containsExactly("prompt.txt", "images/01-earlier-shot-step-source.png", "README.txt");
        String prompt = new String(entries.get("prompt.txt"), StandardCharsets.UTF_8);
        assertThat(prompt)
                .startsWith("STEP SHOT -- EDIT THE ATTACHED IMAGE. The first attached image is shot 1")
                .contains("Shot to produce:")
                .contains("Scene: Priya steps into the hallway")
                .endsWith("Requested change: only Priya stays");
        assertThat(prompt.indexOf("STEP SHOT")).isLessThan(prompt.indexOf("Scene: Priya steps into the hallway"));
        assertThat(ImageIO.read(new ByteArrayInputStream(entries.get("images/01-earlier-shot-step-source.png")))).isNotNull();
        assertThat(new String(entries.get("README.txt"), StandardCharsets.UTF_8))
                .contains("gemini-image-lite")
                .contains("1. images/01-earlier-shot-step-source.png")
                .contains("Same -- use this exact image");
        verifyNoInteractions(llmGatewayClient);
    }

    @Test
    void stepBundleRefusesASourceWithNoImageOfThatKind() {
        Shot source = shot(1, "Priya opens the door");
        Shot target = shot(2, "Priya steps into the hallway");
        given(shotImageRepository.findByShotIdAndKind(source.getId(), ShotImageKind.PRODUCTION)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.stepBundle(tenantId, target.getId(), ShotImageKind.PRODUCTION, source.getId(), null))
                .isInstanceOf(PreProductionException.class)
                .hasMessageContaining("has no image of this kind");
    }

    @Test
    void deleteImageRemovesOnlyThatKindsRow() {
        Shot target = shot(2, "Priya steps into the hallway");
        ShotImage production = image(target, ShotImageKind.PRODUCTION, "shots/2.png");
        given(shotImageRepository.findByShotIdAndKind(target.getId(), ShotImageKind.PRODUCTION)).willReturn(Optional.of(production));

        service.deleteImage(tenantId, target.getId(), ShotImageKind.PRODUCTION);

        verify(shotImageRepository).delete(production);
    }

    @Test
    void deleteImageOfAnotherTenantsShotIsNotFound() {
        UUID foreignShotId = UUID.randomUUID();
        given(shotRepository.findByIdAndTenantId(foreignShotId, tenantId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteImage(tenantId, foreignShotId, ShotImageKind.PRODUCTION))
                .isInstanceOf(PreProductionException.class);
        verify(shotImageRepository, never()).delete(any());
    }

    @Test
    void aStepShotKeepsItsEarlierFrameOnTheLastRetryButAPlainEditDropsIt() {
        assertThat(ShotImageService.identityRefsForAttempt(3, "earlier", true, "face", List.of("other"), "product"))
                .containsExactly("earlier", "face", "product");
        assertThat(ShotImageService.identityRefsForAttempt(3, "current", false, "face", List.of("other"), "product"))
                .containsExactly("face", "product");
        assertThat(ShotImageService.identityRefsForAttempt(1, "earlier", true, "face", List.of("other"), "product"))
                .containsExactly("earlier", "face", "other", "product");
    }

    @Test
    void aRefusalWithAnActorsFaceAttachedNamesTheActorAndWhatToFix() {
        String explained = ShotImageService.refusalExplanation(
                "llm-gateway did not return an image for PRODUCTION shot image generation (finishReason=IMAGE_OTHER)",
                List.of("face: Uma Dixit", "product"));

        assertThat(explained).startsWith("The image model withheld the image on every attempt for this shot with Uma Dixit attached")
                .contains("Remove any real names from the shot");
        // Any other failure, or a refusal with no face attached, keeps its own message.
        assertThat(ShotImageService.refusalExplanation("timeout", List.of("face: Uma Dixit"))).isNull();
        assertThat(ShotImageService.refusalExplanation("finishReason=IMAGE_OTHER", List.of("earlier shot (step source)"))).isNull();
    }

    private Shot shot(int number, String sketch) {
        Shot shot = new Shot();
        shot.setId(UUID.randomUUID());
        shot.setProjectId(projectId);
        shot.setShotNumber(number);
        shot.setSketchPrompt(sketch);
        given(shotRepository.findByIdAndTenantId(shot.getId(), tenantId)).willReturn(Optional.of(shot));
        return shot;
    }

    private ShotImage image(Shot shot, ShotImageKind kind, String objectKey) {
        return ShotImage.builder().id(UUID.randomUUID()).tenantId(tenantId).shotId(shot.getId())
                .kind(kind).bucket("storyboards").objectKey(objectKey).build();
    }

    private void storedPng(String objectKey) throws Exception {
        BufferedImage pixels = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(pixels, "png", png);
        given(minioClient.getObject(any(GetObjectArgs.class))).willAnswer(invocation -> new GetObjectResponse(
                Headers.of(), "storyboards", "", objectKey, new ByteArrayInputStream(png.toByteArray())));
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }
}
