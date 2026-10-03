package com.dalai.llama.preprod.service.creativedirection;

import com.dalai.llama.preprod.domain.CreativeDirectionReviewStatus;
import com.dalai.llama.preprod.domain.ReferenceMediaType;
import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.CreativeDirection;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionGeneration;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionReference;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.domain.entity.Screenplay;
import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.ApprovedCreativeDirectionContext;
import com.dalai.llama.preprod.dto.GenerateScriptRequest;
import com.dalai.llama.preprod.repository.CameraPlanRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionGenerationRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionReferenceRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.repository.ScreenplayRepository;
import com.dalai.llama.preprod.repository.ScreenplaySceneRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.CameraPlanService;
import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.service.ProjectConfigService;
import com.dalai.llama.preprod.service.ScreenplayGenerationService;
import com.dalai.llama.preprod.service.ScriptGenerationService;
import com.dalai.llama.preprod.service.ShotListGenerationService;
import com.dalai.llama.preprod.service.generation.ShotImagePromptBuilder;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.preprod.testsupport.MockedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The approved creative direction -- with the original idea -- must reach every downstream stage,
 * resolved once through CreativeDirectionContextService; legacy projects must keep working. */
class CreativeDirectionPropagationTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID directionId = UUID.randomUUID();
    private final UUID referenceImage = UUID.randomUUID();

    // ------------------------------------------------------------- context resolution

    @Test
    void theApprovedDirectionCarriesTheOriginalIdeaAndItsReferences() {
        CreativeDirectionContextService contexts = contextService(true, true);

        ApprovedCreativeDirectionContext context = contexts.forGeneration(tenantId, projectId).orElseThrow();

        assertThat(context.directionId()).isEqualTo(directionId);
        assertThat(context.idea().title()).isEqualTo("The Verified Difference");
        String block = context.promptBlock();
        assertThat(block).contains("ORIGINAL IDEA", "The Verified Difference", "Trust in home services",
                "APPROVED CREATIVE DIRECTION", "The Diary", "a handwritten diary", "muted teal", "handheld, eye level",
                referenceImage.toString(), "warm window light");
        assertThat(context.imageReferences()).extracting(ApprovedCreativeDirectionContext.Reference::assetId).containsExactly(referenceImage);
    }

    @Test
    void aNewProjectCannotGenerateDependentStagesWithoutAnApprovedDirection() {
        CreativeDirectionContextService contexts = contextService(true, false);

        assertThatThrownBy(() -> contexts.forGeneration(tenantId, projectId)).isInstanceOf(PreProductionException.class)
                .hasMessageContaining("Approve a creative direction");
    }

    @Test
    void aLegacyProjectWithoutADirectionKeepsWorkingAndGetsTheNoneApprovedText() {
        CreativeDirectionContextService contexts = contextService(false, false);

        assertThat(contexts.forGeneration(tenantId, projectId)).isEmpty();
        assertThat(contexts.promptBlock(tenantId, projectId)).isEqualTo(ApprovedCreativeDirectionContext.NONE_APPROVED);
    }

    // ------------------------------------------------------------- hook/beat + script

    @Test
    void hookBeatPlanningAndTheScriptBothReceiveTheDirectionAndTheScriptRecordsWhichOne() {
        MockedService<ScriptGenerationService> built = MockedService.of(ScriptGenerationService.class, new ObjectMapper(), contextService(true, true));
        when(built.dependency(ProjectRepository.class).findByIdAndTenantId(projectId, tenantId))
                .thenReturn(Optional.of(Project.builder().id(projectId).tenantId(tenantId).creativeDirectionRequired(true).build()));
        ProjectConfigService config = built.dependency(ProjectConfigService.class);
        when(config.resolveDialogueLanguage(any(), any(), any())).thenReturn("en-US");
        when(config.resolveNarrativeLanguage(any(), any(), any())).thenReturn("en-US");
        when(built.dependency(ScriptRepository.class).save(any())).thenAnswer(call -> {
            Script script = call.getArgument(0);
            script.setId(UUID.randomUUID());
            return script;
        });
        LlmGatewayClient llm = built.dependency(LlmGatewayClient.class);
        when(llm.chat(anyString(), anyString(), any())).thenAnswer(call -> reply(
                ((LlmGatewayChatRequest) call.getArgument(2)).taskKey().equals("PRE_PROD_HOOK_BEAT_PLAN_GENERATE")
                        ? "{\"hookLine\":\"That sinking feeling\",\"beats\":[]}"
                        : "{\"scriptText\":\"A technician listens first.\",\"noHumans\":false,\"characters\":[]}"));

        var view = built.instance().generate(tenantId, projectId,
                new GenerateScriptRequest("The Verified Difference", 30, null, null, null));

        Map<String, String> sent = variablesByTask(llm);
        assertThat(sent.get("PRE_PROD_HOOK_BEAT_PLAN_GENERATE")).contains("APPROVED CREATIVE DIRECTION", "The Diary", "ORIGINAL IDEA");
        assertThat(sent.get("PRE_PROD_SCRIPT_GENERATE")).contains("APPROVED CREATIVE DIRECTION", "a handwritten diary");
        assertThat(view.creativeDirectionId()).isEqualTo(directionId);
    }

    // ------------------------------------------------------------- screenplay

    @Test
    void screenplayGenerationReceivesTheDirectionAndTheVersionRecordsWhichOne() {
        MockedService<ScreenplayGenerationService> built = MockedService.of(ScreenplayGenerationService.class, new ObjectMapper(), contextService(true, true));
        when(built.dependency(ProjectRepository.class).findByIdAndTenantId(projectId, tenantId))
                .thenReturn(Optional.of(Project.builder().id(projectId).tenantId(tenantId).creativeDirectionRequired(true).build()));
        when(built.dependency(ScriptRepository.class).findByProjectId(projectId))
                .thenReturn(Optional.of(Script.builder().id(UUID.randomUUID()).projectId(projectId).scriptText("A technician listens first.").build()));
        ProjectConfigService config = built.dependency(ProjectConfigService.class);
        when(config.resolveDialogueLanguage(any(), any(), any())).thenReturn("en-US");
        when(config.resolveNarrativeLanguage(any(), any(), any())).thenReturn("en-US");
        ScreenplayRepository screenplays = built.dependency(ScreenplayRepository.class);
        when(screenplays.save(any())).thenAnswer(call -> {
            Screenplay screenplay = call.getArgument(0);
            screenplay.setId(UUID.randomUUID());
            return screenplay;
        });
        when(built.dependency(ScreenplaySceneRepository.class).save(any())).thenAnswer(call -> {
            ScreenplayScene scene = call.getArgument(0);
            scene.setId(UUID.randomUUID());
            return scene;
        });
        LlmGatewayClient llm = built.dependency(LlmGatewayClient.class);
        LlmGatewayChatResponse scenes = reply("{\"scenes\":[{\"sceneNumber\":1,\"slug\":\"INT. FLAT - DAY\",\"summary\":\"He listens.\",\"characterKeys\":[]}]}");
        when(llm.chat(anyString(), anyString(), any())).thenReturn(scenes);

        var view = built.instance().generate(tenantId, projectId);

        assertThat(variablesByTask(llm).get("PRE_PROD_SCREENPLAY_GENERATE")).contains("APPROVED CREATIVE DIRECTION", "ORIGINAL IDEA", "doubt");
        assertThat(view.creativeDirectionId()).isEqualTo(directionId);
    }

    // ------------------------------------------------------------- shot list

    @Test
    void shotPlanningReceivesTheDirection() {
        MockedService<ShotListGenerationService> built = MockedService.of(ShotListGenerationService.class, new ObjectMapper(), contextService(true, true));
        UUID scriptId = UUID.randomUUID();
        UUID screenplayId = UUID.randomUUID();
        when(built.dependency(ProjectRepository.class).findByIdAndTenantId(projectId, tenantId))
                .thenReturn(Optional.of(Project.builder().id(projectId).tenantId(tenantId).build()));
        when(built.dependency(ScriptRepository.class).findByProjectId(projectId))
                .thenReturn(Optional.of(Script.builder().id(scriptId).projectId(projectId).scriptText("A technician listens first.").build()));
        when(built.dependency(ScreenplayRepository.class).findTopByProjectIdOrderByVersionDesc(projectId))
                .thenReturn(Optional.of(Screenplay.builder().id(screenplayId).projectId(projectId).build()));
        when(built.dependency(ScreenplaySceneRepository.class).findByScreenplayIdOrderBySceneNumberAsc(screenplayId))
                .thenReturn(List.of(ScreenplayScene.builder().id(UUID.randomUUID()).sceneNumber(1).slug("INT. FLAT - DAY").build()));

        LlmGatewayChatRequest request = built.instance().buildChatRequest(tenantId, projectId);

        assertThat(request.templateVariables().get("creativeDirection")).contains("APPROVED CREATIVE DIRECTION", "handheld, eye level");
        assertThat(request.templateVariables()).containsKeys("scriptText", "scenes", "characterProfiles", "aspectRatio");
    }

    // ------------------------------------------------------------- camera plan

    @Test
    void cameraPlanningReceivesTheDirection() {
        MockedService<CameraPlanService> built = MockedService.of(CameraPlanService.class, new ObjectMapper(), contextService(true, true));
        UUID shotId = UUID.randomUUID();
        when(built.dependency(ShotRepository.class).findByIdAndTenantId(shotId, tenantId)).thenReturn(Optional.of(
                Shot.builder().id(shotId).tenantId(tenantId).projectId(projectId).shotType(ShotType.ACTION).action("He kneels by the sink").build()));
        when(built.dependency(CameraPlanRepository.class).save(any())).thenAnswer(call -> call.getArgument(0));
        LlmGatewayClient llm = built.dependency(LlmGatewayClient.class);
        LlmGatewayChatResponse plan = reply("{\"blockingMap\":\"camera low\",\"executionSteps\":\"1. SET MARKS\"}");
        when(llm.chat(anyString(), anyString(), any())).thenReturn(plan);

        built.instance().generate(tenantId, shotId);

        assertThat(variablesByTask(llm).get("PRE_PROD_CAMERA_PLAN_GENERATE")).contains("APPROVED CREATIVE DIRECTION", "handheld, eye level");
    }

    // ------------------------------------------------------------- production frames

    @Test
    void productionStillsCarryTheApprovedLookOnTopOfTheShotPrompt() {
        Shot shot = Shot.builder().id(UUID.randomUUID()).projectId(projectId).action("He kneels by the sink").build();
        ApprovedCreativeDirectionContext direction = contextService(true, true).findApproved(projectId).orElseThrow();

        String withDirection = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null, null, List.of(), direction);
        String without = ShotImagePromptBuilder.buildProductionPrompt(shot, null, null, null, List.of());

        assertThat(withDirection).startsWith(without.substring(0, without.indexOf("\nOutput:")));
        assertThat(withDirection).contains("Project look", "The Diary", "Colour treatment: muted teal", "Texture: fine grain",
                "Story period: present day");
        assertThat(withDirection).endsWith(without.substring(without.indexOf("\nOutput:")));
        assertThat(without).doesNotContain("Project look");
    }

    // ------------------------------------------------------------- helpers

    private CreativeDirectionContextService contextService(boolean required, boolean approved) {
        ProjectRepository projects = mock(ProjectRepository.class);
        CreativeDirectionRepository directions = mock(CreativeDirectionRepository.class);
        CreativeDirectionGenerationRepository generations = mock(CreativeDirectionGenerationRepository.class);
        CreativeDirectionReferenceRepository references = mock(CreativeDirectionReferenceRepository.class);
        when(projects.findByIdAndTenantId(projectId, tenantId))
                .thenReturn(Optional.of(Project.builder().id(projectId).tenantId(tenantId).creativeDirectionRequired(required).build()));
        if (approved) {
            UUID generationId = UUID.randomUUID();
            when(directions.findByProjectIdAndReviewStatus(projectId, CreativeDirectionReviewStatus.APPROVED)).thenReturn(Optional.of(
                    CreativeDirection.builder().id(directionId).projectId(projectId).generationId(generationId).version(1)
                            .title("The Diary").creativeConcept("Trust shown, not claimed").directorsTreatment("One technician's day")
                            .colorTreatment("muted teal").texture("fine grain").storyPeriod("present day")
                            .cinematographyPhilosophy("handheld, eye level").signatureCreativeDevice("a handwritten diary").emotionalJourney("doubt to relief")
                            .reviewStatus(CreativeDirectionReviewStatus.APPROVED).createdAt(OffsetDateTime.now()).build()));
            when(generations.findById(generationId)).thenReturn(Optional.of(CreativeDirectionGeneration.builder()
                    .id(generationId).ideaTitle("The Verified Difference").ideaConcept("Trust in home services").build()));
            when(references.findByCreativeDirectionId(directionId)).thenReturn(List.of(CreativeDirectionReference.builder()
                    .assetId(referenceImage).mediaType(ReferenceMediaType.IMAGE).bucket("creator-assets").objectKey("refs/a.jpg")
                    .referenceAnalysis("warm window light").build()));
        }
        return new CreativeDirectionContextService(projects, directions, generations, references, new CreativeDirectionMapper());
    }

    private static LlmGatewayChatResponse reply(String json) {
        LlmGatewayChatResponse response = mock(LlmGatewayChatResponse.class);
        when(response.response()).thenReturn(json);
        return response;
    }

    private static Map<String, String> variablesByTask(LlmGatewayClient llm) {
        ArgumentCaptor<LlmGatewayChatRequest> requests = ArgumentCaptor.forClass(LlmGatewayChatRequest.class);
        verify(llm, atLeastOnce()).chat(anyString(), anyString(), requests.capture());
        return requests.getAllValues().stream().collect(Collectors.toMap(LlmGatewayChatRequest::taskKey,
                request -> String.valueOf(request.templateVariables().get("creativeDirection")), (first, second) -> first));
    }
}
