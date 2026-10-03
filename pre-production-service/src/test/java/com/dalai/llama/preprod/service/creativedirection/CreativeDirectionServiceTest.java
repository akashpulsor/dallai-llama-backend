package com.dalai.llama.preprod.service.creativedirection;

import com.dalai.llama.joblifecycle.JobLifecycleStatus;
import com.dalai.llama.preprod.domain.CreativeDirectionReviewStatus;
import com.dalai.llama.preprod.domain.ReferenceMediaType;
import com.dalai.llama.preprod.domain.ReviewActor;
import com.dalai.llama.preprod.domain.entity.CreativeDirection;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionGeneration;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionReference;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.kafka.ChatJobRequestedEvent;
import com.dalai.llama.preprod.kafka.ChatJobRequestedPublisher;
import com.dalai.llama.preprod.repository.CreativeDirectionFeedbackRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionGenerationRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionReferenceRepository;
import com.dalai.llama.preprod.repository.CreativeDirectionRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.service.creativeplanning.CreativePlanningClient;
import com.dalai.llama.preprod.service.creativeplanning.CreativePlanningClient.CreativeContext;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.preprod.testsupport.MockedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreativeDirectionServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID image = UUID.randomUUID();
    private final UUID video = UUID.randomUUID();

    private MockedService<CreativeDirectionService> built;
    private CreativeDirectionService service;
    private CreativeDirectionRepository directions;
    private CreativeDirectionReferenceRepository references;
    private CreativeDirectionGenerationRepository generations;
    private LlmGatewayClient llm;
    private final List<CreativeDirection> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        built = MockedService.of(CreativeDirectionService.class, new ObjectMapper(), new CreativeDirectionMapper());
        service = built.instance();
        directions = built.dependency(CreativeDirectionRepository.class);
        references = built.dependency(CreativeDirectionReferenceRepository.class);
        generations = built.dependency(CreativeDirectionGenerationRepository.class);
        llm = built.dependency(LlmGatewayClient.class);

        Project project = Project.builder().id(projectId).tenantId(tenantId).creativeDirectionRequired(true).build();
        when(built.dependency(ProjectRepository.class).findByIdAndTenantId(projectId, tenantId)).thenReturn(Optional.of(project));
        when(built.dependency(CreativePlanningClient.class).getCreativeContext(tenantId, projectId)).thenReturn(context());
        when(generations.save(any())).thenAnswer(call -> {
            CreativeDirectionGeneration generation = call.getArgument(0);
            generation.setId(UUID.randomUUID());
            return generation;
        });
        when(directions.save(any())).thenAnswer(call -> {
            CreativeDirection direction = call.getArgument(0);
            if (direction.getId() == null) {
                direction.setId(UUID.randomUUID());
                saved.add(direction);
            }
            return direction;
        });
    }

    @Test
    void generationIsSubmittedAsAPendingRoundCarryingTheRequestedCount() {
        when(generations.findTopByProjectIdAndStatusOrderByRoundDesc(projectId, JobLifecycleStatus.PENDING)).thenReturn(Optional.empty());

        service.generate(tenantId, projectId, UUID.randomUUID(), 8);

        ArgumentCaptor<CreativeDirectionGeneration> round = ArgumentCaptor.forClass(CreativeDirectionGeneration.class);
        verify(generations).save(round.capture());
        assertThat(round.getValue().getStatus()).isEqualTo(JobLifecycleStatus.PENDING);
        assertThat(round.getValue().getRequestedCount()).isEqualTo(8);
        assertThat(round.getValue().getIdeaTitle()).isEqualTo("The Verified Difference");

        ArgumentCaptor<ChatJobRequestedEvent> event = ArgumentCaptor.forClass(ChatJobRequestedEvent.class);
        verify(built.dependency(ChatJobRequestedPublisher.class)).publish(event.capture());
        assertThat(event.getValue().idempotencyKey()).isEqualTo(round.getValue().getLlmIdempotencyKey());
        LlmGatewayChatRequest request = event.getValue().request();
        assertThat(request.taskKey()).isEqualTo("PRE_PROD_CREATIVE_DIRECTION_GENERATE");
        assertThat(request.templateVariables()).containsEntry("directionCount", "8")
                .containsKeys("idea", "brief", "durationSeconds", "references", "referenceAnalysis");
        assertThat(request.templateVariables().get("references")).contains(image.toString()).contains("VIDEO");
        verify(directions, never()).save(any());
    }

    @Test
    void askingForFewerThanFiveOrMoreThanTenIsRefused() {
        assertThatThrownBy(() -> service.generate(tenantId, projectId, null, 4)).isInstanceOf(PreProductionException.class);
        assertThatThrownBy(() -> service.generate(tenantId, projectId, null, 11)).isInstanceOf(PreProductionException.class);
    }

    @Test
    void aSecondPressWhileARoundIsRunningDoesNotStartAnother() {
        CreativeDirectionGeneration running = round(6);
        when(generations.findTopByProjectIdAndStatusOrderByRoundDesc(projectId, JobLifecycleStatus.PENDING))
                .thenReturn(Optional.of(running));

        service.generate(tenantId, projectId, null, 6);

        verify(built.dependency(ChatJobRequestedPublisher.class), never()).publish(any());
    }

    @Test
    void aCompletedRoundStoresAdvisoryDirectionsWithEveryFieldInItsOwnColumn() {
        CreativeDirectionGeneration round = round(5);
        String reply = """
                {"recommendationReason":"Only the diary device carries the trust story in 30 seconds",
                 "directions":[%s,%s,%s,%s,%s]}""".formatted(
                direction("The Diary", "[\"" + image + "\"]"),
                direction("Night Shift", "[\"" + video + "\", \"" + UUID.randomUUID() + "\", \"not-a-uuid\"]"),
                direction("Open Door", "[]"), direction("Two Kitchens", "[]"), direction("The Checklist", "[]"));

        service.completeGeneration(round.getId(), response(reply));

        assertThat(saved).hasSize(5);
        CreativeDirection recommended = saved.get(0);
        assertThat(recommended.isRecommended()).isTrue();
        assertThat(recommended.getRecommendationReason()).isEqualTo("Only the diary device carries the trust story in 30 seconds");
        assertThat(recommended.getTitle()).isEqualTo("The Diary");
        assertThat(recommended.getColorTreatment()).isEqualTo("muted teal");
        assertThat(recommended.getStoryPeriod()).isEqualTo("present day");
        assertThat(recommended.getSignatureCreativeDevice()).isEqualTo("a handwritten diary");
        assertThat(saved).allSatisfy(direction -> assertThat(direction.getReviewStatus()).isEqualTo(CreativeDirectionReviewStatus.PROPOSED));
        assertThat(saved.subList(1, 5)).allSatisfy(direction -> {
            assertThat(direction.isRecommended()).isFalse();
            assertThat(direction.getRecommendationReason()).isNull();
        });
        assertThat(round.getStatus()).isEqualTo(JobLifecycleStatus.COMPLETED);

        ArgumentCaptor<CreativeDirectionReference> refs = ArgumentCaptor.forClass(CreativeDirectionReference.class);
        verify(references, atLeastOnce()).save(refs.capture());
        // Only real assets on the brief are associated: the invented and malformed ids are dropped.
        assertThat(refs.getAllValues()).extracting(CreativeDirectionReference::getAssetId).containsExactly(image, video);
        assertThat(refs.getAllValues().get(0).getMediaType()).isEqualTo(ReferenceMediaType.IMAGE);
        assertThat(refs.getAllValues().get(0).getReferenceAnalysis()).isEqualTo("warm window light");
        assertThat(refs.getAllValues().get(1).getClientInstruction()).isEqualTo("show the real technician");
    }

    @Test
    void aReplyWithTheWrongNumberOfDirectionsIsNeverPersisted() {
        CreativeDirectionGeneration round = round(6);
        String reply = """
                {"recommendationReason":"r","directions":[%s,%s,%s]}""".formatted(direction("A", "[]"), direction("B", "[]"), direction("C", "[]"));

        assertThatThrownBy(() -> service.completeGeneration(round.getId(), response(reply))).isInstanceOf(PreProductionException.class);
        verify(directions, never()).save(any());
    }

    @Test
    void anyDirectionCanBeApprovedAndThePreviousApprovalIsSupersededNotDeleted() {
        CreativeDirection previous = stored(CreativeDirectionReviewStatus.APPROVED, true);
        CreativeDirection alternative = stored(CreativeDirectionReviewStatus.PROPOSED, false);
        when(directions.findByProjectIdAndReviewStatus(projectId, CreativeDirectionReviewStatus.APPROVED)).thenReturn(Optional.of(previous));

        service.approve(tenantId, projectId, alternative.getId(), ReviewActor.CLIENT, null);

        assertThat(alternative.getReviewStatus()).isEqualTo(CreativeDirectionReviewStatus.APPROVED);
        assertThat(alternative.getApprovedVia()).isEqualTo(ReviewActor.CLIENT);
        assertThat(alternative.isRecommended()).isFalse();
        assertThat(previous.getReviewStatus()).isEqualTo(CreativeDirectionReviewStatus.SUPERSEDED);
    }

    @Test
    void aNewRoundSupersedesOpenDirectionsButNeverTheApprovedOne() {
        CreativeDirection open = stored(CreativeDirectionReviewStatus.SELECTED, false);
        CreativeDirection approved = stored(CreativeDirectionReviewStatus.APPROVED, true);
        when(directions.findByProjectIdAndReviewStatusIn(eq(projectId), any())).thenReturn(List.of(open));
        CreativeDirectionGeneration round = round(5);
        String reply = """
                {"recommendationReason":"r","directions":[%s,%s,%s,%s,%s]}""".formatted(
                direction("A", "[]"), direction("B", "[]"), direction("C", "[]"), direction("D", "[]"), direction("E", "[]"));

        service.completeGeneration(round.getId(), response(reply));

        assertThat(open.getReviewStatus()).isEqualTo(CreativeDirectionReviewStatus.SUPERSEDED);
        assertThat(approved.getReviewStatus()).isEqualTo(CreativeDirectionReviewStatus.APPROVED);
    }

    @Test
    void revisingTheApprovedDirectionCreatesANewVersionAndLeavesTheApprovalIntact() {
        CreativeDirection approved = stored(CreativeDirectionReviewStatus.APPROVED, true);
        when(built.dependency(CreativeDirectionFeedbackRepository.class).findByCreativeDirectionIdOrderByCreatedAtAsc(approved.getId()))
                .thenReturn(List.of());
        reply(direction("The Diary, warmer", "[]"));

        service.revise(tenantId, projectId, approved.getId(), "Warmer, less clinical");

        assertThat(approved.getReviewStatus()).isEqualTo(CreativeDirectionReviewStatus.APPROVED);
        assertThat(approved.getTitle()).isEqualTo("The Diary");
        CreativeDirection revision = saved.get(saved.size() - 1);
        assertThat(revision.getVersion()).isEqualTo(2);
        assertThat(revision.getRevisedFromId()).isEqualTo(approved.getId());
        assertThat(revision.getTitle()).isEqualTo("The Diary, warmer");
        assertThat(revision.getReviewStatus()).isEqualTo(CreativeDirectionReviewStatus.SELECTED);
    }

    private CreativeDirection stored(CreativeDirectionReviewStatus status, boolean recommended) {
        CreativeDirection direction = CreativeDirection.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId).generationId(UUID.randomUUID())
                .optionNumber(recommended ? 1 : 2).version(1).title("The Diary").creativeConcept("c").directorsTreatment("t")
                .recommended(recommended).reviewStatus(status).createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
        when(directions.findByIdAndProjectIdAndTenantId(direction.getId(), projectId, tenantId)).thenReturn(Optional.of(direction));
        return direction;
    }

    private void reply(String json) {
        LlmGatewayChatResponse response = response(json);
        when(llm.chat(anyString(), anyString(), any())).thenReturn(response);
    }

    private static LlmGatewayChatResponse response(String json) {
        LlmGatewayChatResponse response = mock(LlmGatewayChatResponse.class);
        when(response.response()).thenReturn(json);
        return response;
    }

    private CreativeDirectionGeneration round(int requestedCount) {
        CreativeDirectionGeneration round = CreativeDirectionGeneration.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).projectId(projectId).round(1).requestedCount(requestedCount)
                .status(JobLifecycleStatus.PENDING).llmIdempotencyKey("creative-direction-" + projectId + "-round1")
                .createdAt(OffsetDateTime.now()).build();
        when(generations.findById(round.getId())).thenReturn(Optional.of(round));
        return round;
    }

    private static String direction(String title, String referenceIds) {
        return """
                {"title":"%s","creativeConcept":"Trust shown, not claimed","directorsTreatment":"Told through one technician's day",
                 "storytellingStyle":"observational","visualLanguage":{"storyPeriod":"present day","colorTreatment":"muted teal",
                 "contrast":"soft","texture":"fine grain","overallAesthetic":"documentary"},
                 "cinematographyPhilosophy":"handheld, eye level","emotionalJourney":"doubt to relief","soundDirection":"room tone, sparse piano",
                 "signatureCreativeDevice":"a handwritten diary","creativeRationale":"Proof over promise","referenceAssetIds":%s}"""
                .formatted(title, referenceIds);
    }

    private CreativeContext context() {
        return new CreativeContext(UUID.randomUUID(),
                new CreativeContext.Idea("The Verified Difference", "Trust in home services", "urban professionals",
                        "9% vs 47%", "Vetted pros", "reassuring"),
                new CreativeContext.Brief(UUID.randomUUID(), "City Professionals launch film", null, null, 30),
                List.of(new CreativeContext.ReferenceAsset(image, ReferenceMediaType.IMAGE, "creator-assets", "refs/a.jpg",
                                "https://signed/a", null, null, null, "warm window light"),
                        new CreativeContext.ReferenceAsset(video, ReferenceMediaType.VIDEO, "creator-assets", "refs/b.mp4",
                                "https://signed/b", "video/mp4", "b.mp4", "show the real technician", null)));
    }
}
