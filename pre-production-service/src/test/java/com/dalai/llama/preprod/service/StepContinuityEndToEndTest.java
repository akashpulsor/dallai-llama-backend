package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.MoodProfile;
import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.TimeOfDay;
import com.dalai.llama.preprod.domain.entity.LightingPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotContinuityOverride;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.domain.entity.StepContinuityAnalysisRecord;
import com.dalai.llama.preprod.dto.StepContinuityView;
import com.dalai.llama.preprod.repository.CameraPlanRepository;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.LightingPlanRepository;
import com.dalai.llama.preprod.repository.MotionGraphicPlanRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneCharacterRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotContinuityOverrideRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.repository.ShotProductReferenceRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.repository.StepContinuityAnalysisRepository;
import com.dalai.llama.preprod.service.continuity.ContinuitySource;
import com.dalai.llama.preprod.service.continuity.ResolutionSeverity;
import com.dalai.llama.preprod.service.continuity.StepContinuityService;
import com.dalai.llama.preprod.service.continuity.VisualField;
import com.dalai.llama.preprod.service.creativedirection.CreativeDirectionContextService;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The night city -> high-speed train step, through the real service: the shot's metadata and
 * lighting plan say MIDDAY / HIGH_KEY / midday sun; the (mocked) analysis model reports what the
 * reference image shows; the final prompt must be written from the resolved state only.
 */
class StepContinuityEndToEndTest {

    private static final String ANALYSIS = """
            {
              "referenceSummary": "Blue-hour Indian city: lit glass towers, amber highway lights, neon blue accents",
              "fields": [
                {"field": "TIME_OF_DAY", "reference": "blue hour / night", "transition": null, "candidates": [
                  {"input": "SHOT_TIME_OF_DAY", "relation": "CONFLICT",
                   "reason": "The previous shot is at night and this shot does not ask for a time change.", "compatibleRewrite": null},
                  {"input": "LIGHTING_KEY", "relation": "CONFLICT", "reason": "Midday sun cannot exist at night.",
                   "compatibleRewrite": "Use the existing city practicals and architectural light for crisp, directional highlights"}
                ]},
                {"field": "LIGHTING", "reference": "low-key neon: cool blue architectural light, warm amber highway light", "transition": null, "candidates": [
                  {"input": "SHOT_LIGHTING_MOOD", "relation": "CONFLICT",
                   "reason": "High-key daylight lighting conflicts with the established night environment.",
                   "compatibleRewrite": "crisp, energetic separation from the existing night illumination and neon accents"},
                  {"input": "LIGHTING_KEY", "relation": "CONFLICT", "reason": "Daylight key light.",
                   "compatibleRewrite": "Use the existing city practicals and architectural light for crisp, directional highlights"},
                  {"input": "LIGHTING_FILL", "relation": "CONFLICT", "reason": "High-key fill.",
                   "compatibleRewrite": "Let the illuminated windows lift the shadow side slightly, keeping the night contrast"},
                  {"input": "SOMETHING_THE_MODEL_INVENTED", "relation": "CONFLICT", "reason": "x", "compatibleRewrite": "x"}
                ]},
                {"field": "ARCHITECTURE", "reference": "glass towers and elevated highways", "transition": null, "candidates": []},
                {"field": "NOT_A_FIELD", "reference": "ignored", "transition": null, "candidates": []}
              ],
              "changesForThisShot": ["introduce a sleek high-speed train travelling horizontally through the mid-frame on an elevated track"],
              "newElementLighting": "The train inherits the scene's light: its metal picks up cool blue reflections from the towers and warm amber from the highway."
            }
            """;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final ShotRepository shotRepository = mock(ShotRepository.class);
    private final ShotImageRepository shotImageRepository = mock(ShotImageRepository.class);
    private final LightingPlanRepository lightingPlanRepository = mock(LightingPlanRepository.class);
    private final StepContinuityAnalysisRepository analysisRepository = mock(StepContinuityAnalysisRepository.class);
    private final ShotContinuityOverrideRepository overrideRepository = mock(ShotContinuityOverrideRepository.class);
    private final MinioClient minioClient = mock(MinioClient.class);
    private final LlmGatewayClient llmGatewayClient = mock(LlmGatewayClient.class);
    private final StepContinuityService continuity = new StepContinuityService(analysisRepository, overrideRepository, shotRepository,
            mock(ScreenplaySceneRepository.class), llmGatewayClient, new ObjectMapper(), "gemini-text");
    private final ShotImageService service = new ShotImageService(
            shotRepository, mock(ScriptRepository.class), mock(ScriptCharacterRepository.class),
            mock(ScreenplaySceneCharacterRepository.class), mock(CastAssignmentRepository.class),
            mock(CastProfileRepository.class), shotImageRepository, mock(ShotProductReferenceRepository.class),
            lightingPlanRepository, mock(CameraPlanRepository.class), mock(MotionGraphicPlanRepository.class),
            llmGatewayClient, mock(MediaAssetService.class), mock(GenerationThoughtService.class),
            mock(ShotImageDescriptionService.class), minioClient, mock(MinioClient.class), new ObjectMapper(),
            "storyboards", "shots", "gemini-image", "gemini-image", "flux-schnell", "gemini-image-lite", "gemini-text",
            mock(CreativeDirectionContextService.class), continuity);

    private Shot cityShot;
    private Shot trainShot;
    private final AtomicReference<StepContinuityAnalysisRecord> cachedAnalysis = new AtomicReference<>();
    private final List<ShotContinuityOverride> storedOverrides = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        cityShot = shot(1, "Drones fly over a sprawling modern Indian city", TimeOfDay.NIGHT, MoodProfile.LOW_KEY);
        trainShot = shot(2, "A high-speed train whizzes past a modern industrial complex", TimeOfDay.MIDDAY, MoodProfile.HIGH_KEY);
        trainShot.setLocation("Modern Indian Cityscapes");
        trainShot.setCineLensFocalLength("35mm");
        trainShot.setCameraShotSize(com.dalai.llama.preprod.domain.ShotSize.WS);
        given(lightingPlanRepository.findByShotId(trainShot.getId())).willReturn(Optional.of(LightingPlan.builder()
                .keyLightGear("A bright LED desk lamp angled to mimic harsh, directional midday sun")
                .fillLightGear("A white foam board lifting shadows for a high-key look")
                .build()));
        given(shotImageRepository.findByShotIdAndKind(cityShot.getId(), ShotImageKind.PRODUCTION)).willReturn(Optional.of(
                ShotImage.builder().id(UUID.randomUUID()).tenantId(tenantId).shotId(cityShot.getId())
                        .kind(ShotImageKind.PRODUCTION).bucket("storyboards").objectKey("shots/1.png").build()));
        storedPng();

        given(llmGatewayClient.chat(anyString(), anyString(), any())).willReturn(
                new LlmGatewayChatResponse(UUID.randomUUID(), "gemini-text", ANALYSIS, null, 10, "STOP"));
        given(analysisRepository.findByShotIdAndSourceShotIdAndKind(any(), any(), any()))
                .willAnswer(invocation -> Optional.ofNullable(cachedAnalysis.get()));
        given(analysisRepository.save(any())).willAnswer(invocation -> {
            cachedAnalysis.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        given(overrideRepository.findByShotId(trainShot.getId())).willAnswer(invocation -> List.copyOf(storedOverrides));
        given(overrideRepository.findByShotIdAndField(any(), any())).willAnswer(invocation -> storedOverrides.stream()
                .filter(o -> o.getField() == invocation.getArgument(1)).findFirst());
        given(overrideRepository.save(any())).willAnswer(invocation -> {
            ShotContinuityOverride row = invocation.getArgument(0);
            if (!storedOverrides.contains(row)) storedOverrides.add(row);
            return row;
        });
        org.mockito.Mockito.doAnswer(invocation -> storedOverrides.remove(invocation.<ShotContinuityOverride>getArgument(0)))
                .when(overrideRepository).delete(any());
    }

    @Test
    void theTrainShotKeepsTheNightAndNoDaylightInstructionSurvives() {
        StepContinuityView view = preview();
        String prompt = view.finalPrompt();

        assertThat(prompt)
                .startsWith("STEP SHOT -- EDIT THE ATTACHED IMAGE")
                .contains("CRITICAL VISUAL CONTINUITY")
                .contains("- Time of day: blue hour / night")
                .contains("- Lighting: low-key neon: cool blue architectural light, warm amber highway light")
                .contains("- introduce a sleek high-speed train travelling horizontally")
                .contains("New elements: The train inherits the scene's light")
                .contains("Do not: change the time of day; relight the environment")
                .contains("Location: Modern Indian Cityscapes, blue hour / night")
                .contains("Lighting mood: crisp, energetic separation from the existing night illumination")
                .contains("- Key light: Use the existing city practicals and architectural light for crisp, directional highlights")
                // the camera still changes
                .contains("focal length 35mm");
        assertThat(prompt.toLowerCase()).doesNotContain("midday").doesNotContain("high_key").doesNotContain("high-key look");
        assertThat(view.warnings()).isEmpty();
    }

    @Test
    void theUiGetsBothOverridesWithTheirOriginalValuesSourcesAndReasons() {
        StepContinuityView view = preview();

        assertThat(view.overrides()).extracting(o -> o.field() + ": " + o.originalValue() + " -> " + o.resolvedValue()
                        + " [" + o.originalSource() + " -> " + o.resolvedSource() + ", " + o.severity() + "]")
                .containsExactly(
                        "TIME_OF_DAY: MIDDAY -> blue hour / night [SHOT_METADATA -> REFERENCE_IMAGE, OVERRIDE]",
                        "LIGHTING: HIGH_KEY -> low-key neon: cool blue architectural light, warm amber highway light [SHOT_METADATA -> REFERENCE_IMAGE, OVERRIDE]");
        assertThat(view.overrides()).allSatisfy(o -> assertThat(o.reason()).isNotBlank());
        assertThat(view.preserved()).containsExactly(VisualField.ARCHITECTURE);
        assertThat(view.resolvedVisualState().get(VisualField.TIME_OF_DAY).source()).isEqualTo(ContinuitySource.REFERENCE_IMAGE);
        verify(llmGatewayClient).chat(anyString(), anyString(),
                argThat((LlmGatewayChatRequest request) -> "PRE_PROD_STEP_CONTINUITY".equals(request.taskKey())));
    }

    @Test
    void choosingMiddayRewritesThePromptAndRemovingTheChoiceRestoresContinuityWithoutAnotherAnalysis() {
        assertThat(preview().finalPrompt()).contains("Location: Modern Indian Cityscapes, blue hour / night");

        continuity.setOverride(tenantId, trainShot.getId(), VisualField.TIME_OF_DAY, "MIDDAY");
        StepContinuityView chosen = preview();
        assertThat(chosen.finalPrompt()).contains("Location: Modern Indian Cityscapes, MIDDAY")
                .contains("- Time of day: MIDDAY -- a deliberate change")
                .doesNotContain("- Time of day: blue hour / night");
        assertThat(chosen.overrides()).filteredOn(o -> o.field() == VisualField.TIME_OF_DAY).singleElement()
                .satisfies(o -> assertThat(o.severity()).isEqualTo(ResolutionSeverity.USER_OVERRIDE));

        continuity.clearOverride(tenantId, trainShot.getId(), VisualField.TIME_OF_DAY);
        assertThat(preview().finalPrompt()).contains("Location: Modern Indian Cityscapes, blue hour / night");

        // One analysis served all three resolutions.
        verify(llmGatewayClient, times(1)).chat(anyString(), anyString(), any());
    }

    @Test
    void aSupersededValueThatStillReachesThePromptIsReportedNotShippedSilently() {
        trainShot.setAction("A high-speed train whizzes past at MIDDAY");

        StepContinuityView view = preview();

        assertThat(view.warnings()).singleElement().asString()
                .contains("Time of day: \"MIDDAY\" (Shot metadata) still appears in the prompt");
    }

    @Test
    void whenTheAnalysisFailsTheStepStillWorksWithTheGenericInstructionAndSaysSo() {
        given(llmGatewayClient.chat(anyString(), anyString(), any())).willReturn(
                new LlmGatewayChatResponse(UUID.randomUUID(), "gemini-text", "", null, 10, "STOP"));

        StepContinuityView view = preview();

        assertThat(view.finalPrompt()).contains("Keep from the image: the location, the light, the colour grade");
        assertThat(view.warnings()).singleElement().asString().startsWith("Continuity check unavailable");
    }

    private StepContinuityView preview() {
        return service.previewStep(tenantId, trainShot.getId(), ShotImageKind.PRODUCTION, cityShot.getId(), null);
    }

    private Shot shot(int number, String action, TimeOfDay timeOfDay, MoodProfile mood) {
        Shot shot = Shot.builder().id(UUID.randomUUID()).projectId(projectId).shotNumber(number).shotRef("shot-01-00" + number)
                .action(action).timeOfDay(timeOfDay).lightingMood(mood).aspectRatio(null).build();
        given(shotRepository.findByIdAndTenantId(shot.getId(), tenantId)).willReturn(Optional.of(shot));
        return shot;
    }

    private void storedPng() throws Exception {
        BufferedImage pixels = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(pixels, "png", png);
        given(minioClient.getObject(any(GetObjectArgs.class))).willAnswer(invocation -> new GetObjectResponse(
                Headers.of(), "storyboards", "", "shots/1.png", new ByteArrayInputStream(png.toByteArray())));
    }
}
