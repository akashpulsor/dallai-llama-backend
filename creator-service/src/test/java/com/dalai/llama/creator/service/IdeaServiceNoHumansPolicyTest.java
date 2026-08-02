package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorIdea;
import com.dalai.llama.creator.dto.request.SaveStoryScriptRequest;
import com.dalai.llama.creator.dto.response.GeneratedScriptResponse;
import com.dalai.llama.creator.dto.response.GeneratedStoryScriptResponse;
import com.dalai.llama.creator.repository.CreatorIdeaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdeaServiceNoHumansPolicyTest {

    @Test
    void readsNoHumansFromNestedProductCreativeDirection() {
        Map<String, Object> sourceBrief = Map.of(
                "briefMode", "product_ad_agent",
                "productIntelligenceBrief", Map.of(
                        "creativeDirection", Map.of("noHumans", true)
                )
        );

        assertThat(service().isNoHumanProductAdBrief(sourceBrief)).isTrue();
    }

    @Test
    void addsMultimodalContextOnlyForProductBriefs() {
        IdeaService service = service();
        Map<String, Object> productInput = new java.util.LinkedHashMap<>();
        service.putProductReferenceAiContext(productInput, Map.of(
                "briefMode", "product_ad_agent",
                "productIntelligenceBrief", Map.of(
                        "imageUrls", List.of("https://example.com/front.png", "https://example.com/back.png"),
                        "ingredientDetails", "Cocoa, peanuts, peanut butter",
                        "targetAudience", "Health-conscious professionals",
                        "campaignObjective", "Drive trial"
                )
        ));

        assertThat(productInput)
                .containsEntry("attachReferenceImages", true)
                .containsEntry("ingredientDetails", "Cocoa, peanuts, peanut butter")
                .containsEntry("targetAudience", "Health-conscious professionals")
                .containsEntry("campaignObjective", "Drive trial");
        assertThat(productInput.get("referenceImageUrls")).asList()
                .containsExactly("https://example.com/front.png", "https://example.com/back.png");

        Map<String, Object> ordinaryStoryboardInput = new java.util.LinkedHashMap<>();
        service.putProductReferenceAiContext(ordinaryStoryboardInput, Map.of(
                "title", "A founder explains procrastination",
                "summary", "An ordinary creator story"
        ));
        assertThat(ordinaryStoryboardInput)
                .doesNotContainKeys("attachReferenceImages", "referenceImageUrls", "productReferenceImageUrls");
    }

    @Test
    void replacesHumanLedStoryWithProductOnlyNarrative() {
        IdeaService service = service();
        Map<String, Object> sourceBrief = productOnlyBrief();
        CreatorIdea idea = CreatorIdea.builder()
                .title("Priya finds a healthy indulgence")
                .selectionContext(Map.of(
                        "generatedIndex", 1,
                        "creativeNotes", Map.of("adConceptLane", "problem_solution")
                ))
                .build();
        GeneratedStoryScriptResponse.StoryScript story = GeneratedStoryScriptResponse.StoryScript.builder()
                .projectTitle("Priya's choice")
                .logline("Priya wants a snack.")
                .centralConflict("Priya must decide.")
                .storyline("Priya discovers the product.")
                .hook("Priya reaches for a snack.")
                .endingPayoff("Priya smiles.")
                .characters(List.of(
                        GeneratedStoryScriptResponse.CharacterProfile.builder()
                                .name("Priya")
                                .role("Customer")
                                .build()
                ))
                .beats(List.of(
                        GeneratedStoryScriptResponse.StoryBeat.builder()
                                .beatNumber(1)
                                .title("Priya decides")
                                .summary("Priya reaches for the pack.")
                                .characterFocus("Priya")
                                .estimatedSeconds(30)
                                .build()
                ))
                .build();

        service.applyNoHumanProductStoryContract(story, idea, sourceBrief, 30);

        assertThat(story.getNoHumans()).isTrue();
        assertThat(story.getNarrativeMode()).isEqualTo("product_only");
        assertThat(story.getDialogueMode()).isEqualTo("off_screen_voiceover");
        assertThat(story.getCharacters()).isEmpty();
        assertThat(story.getProjectTitle()).contains("Double Chocolate Peanut Butter Bar");
        assertThat(List.of(
                story.getLogline(),
                story.getCentralConflict(),
                story.getStoryline(),
                story.getHook(),
                story.getEndingPayoff()
        ))
                .noneMatch(value -> value.contains("Priya"));
        assertThat(story.getBeats()).hasSize(4).allSatisfy(beat -> {
            assertThat(beat.getSummary()).doesNotContain("Priya");
            assertThat(beat.getCharacterFocus()).doesNotContain("Priya");
        });
    }

    @Test
    void preservesUserEditedProductStoryDuringSave() {
        IdeaService service = service();
        GeneratedStoryScriptResponse.StoryScript story = GeneratedStoryScriptResponse.StoryScript.builder()
                .projectTitle("A custom product title")
                .storyline("A user-authored product story with a precise reveal and payoff.")
                .hook("Open on the ingredient impact.")
                .endingPayoff("Resolve on the exact pack and custom CTA.")
                .characters(List.of(
                        GeneratedStoryScriptResponse.CharacterProfile.builder()
                                .name("Legacy generated customer")
                                .build()
                ))
                .beats(List.of(
                        GeneratedStoryScriptResponse.StoryBeat.builder()
                                .beatNumber(1)
                                .title("Custom macro hook")
                                .summary("The product texture creates visual tension.")
                                .build()
                ))
                .llmGeneratedScript(Map.of("storyline", "Original generated storyline"))
                .userRevision(Map.of("storyline", "A user-authored product story with a precise reveal and payoff."))
                .revisionAudit(Map.of("revisionNumber", 2, "edited", true))
                .build();

        service.applyNoHumanProductStorySaveContract(story);

        assertThat(story.getProjectTitle()).isEqualTo("A custom product title");
        assertThat(story.getStoryline()).isEqualTo("A user-authored product story with a precise reveal and payoff.");
        assertThat(story.getHook()).isEqualTo("Open on the ingredient impact.");
        assertThat(story.getEndingPayoff()).isEqualTo("Resolve on the exact pack and custom CTA.");
        assertThat(story.getBeats()).singleElement()
                .satisfies(beat -> assertThat(beat.getTitle()).isEqualTo("Custom macro hook"));
        assertThat(story.getLlmGeneratedScript()).containsEntry("storyline", "Original generated storyline");
        assertThat(story.getUserRevision()).containsEntry("storyline", "A user-authored product story with a precise reveal and payoff.");
        assertThat(story.getRevisionAudit()).containsEntry("revisionNumber", 2);
        assertThat(story.getNoHumans()).isTrue();
        assertThat(story.getNarrativeMode()).isEqualTo("product_only");
        assertThat(story.getDialogueMode()).isEqualTo("off_screen_voiceover");
        assertThat(story.getCharacters()).isEmpty();
    }

    @Test
    void saveStoryScriptPersistsEditedNoHumanNarrativeInsteadOfRegeneratingIt() {
        CreatorIdeaRepository ideaRepository = mock(CreatorIdeaRepository.class);
        CreatorProjectService projectService = mock(CreatorProjectService.class);
        UUID lockedIdeaId = UUID.randomUUID();
        UUID storyIdeaId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String tenantId = "tenant-1";
        String userId = "creator-1";
        CreatorIdea lockedIdea = CreatorIdea.builder()
                .id(lockedIdeaId)
                .tenantId(tenantId)
                .userId(userId)
                .projectId(projectId)
                .source("ORIGINAL")
                .title("Product campaign")
                .durationSeconds(30)
                .selectionContext(productOnlyBrief())
                .build();
        CreatorIdea storyIdea = CreatorIdea.builder()
                .id(storyIdeaId)
                .tenantId(tenantId)
                .userId(userId)
                .projectId(projectId)
                .source("AI_GENERATED")
                .title("Generated product story")
                .durationSeconds(30)
                .selectionContext(Map.of("parentLockedIdeaId", lockedIdeaId.toString()))
                .build();
        GeneratedStoryScriptResponse.StoryScript editedStory = GeneratedStoryScriptResponse.StoryScript.builder()
                .projectTitle("Creator-edited product title")
                .duration(30)
                .dialogueLanguage("English")
                .screenType("vertical")
                .storytellingType("visual_voiceover")
                .hookLens("direct")
                .storyline("Keep this exact creator-edited product storyline in the database.")
                .hook("Keep this custom ingredient hook.")
                .endingPayoff("Keep this custom product payoff.")
                .characters(List.of(
                        GeneratedStoryScriptResponse.CharacterProfile.builder().name("Legacy customer").build()
                ))
                .beats(List.of(
                        GeneratedStoryScriptResponse.StoryBeat.builder()
                                .beatNumber(1)
                                .title("Creator-edited opening")
                                .summary("A custom product-only macro reveal.")
                                .build()
                ))
                .llmGeneratedScript(Map.of("storyline", "Original generated storyline"))
                .userRevision(Map.of("storyline", "Keep this exact creator-edited product storyline in the database."))
                .revisionAudit(Map.of("revisionNumber", 3, "edited", true))
                .build();
        SaveStoryScriptRequest request = new SaveStoryScriptRequest(
                editedStory.getProjectTitle(),
                30,
                "English",
                "vertical",
                "visual_voiceover",
                "direct",
                "Creator-edited script text",
                editedStory
        );
        when(ideaRepository.findById(lockedIdeaId)).thenReturn(Optional.of(lockedIdea));
        when(ideaRepository.findById(storyIdeaId)).thenReturn(Optional.of(storyIdea));
        when(ideaRepository.save(any(CreatorIdea.class))).thenAnswer(invocation -> invocation.getArgument(0));
        IdeaService service = new IdeaService(
                ideaRepository,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                projectService,
                null,
                null,
                null,
                new ObjectMapper()
        );

        GeneratedStoryScriptResponse response = service.saveStoryScript(
                lockedIdeaId,
                storyIdeaId,
                request,
                tenantId,
                userId
        );

        assertThat(response.getScriptJson().getStoryline())
                .isEqualTo("Keep this exact creator-edited product storyline in the database.");
        assertThat(response.getScriptJson().getHook()).isEqualTo("Keep this custom ingredient hook.");
        assertThat(response.getScriptJson().getEndingPayoff()).isEqualTo("Keep this custom product payoff.");
        assertThat(response.getScriptJson().getBeats()).singleElement()
                .satisfies(beat -> assertThat(beat.getTitle()).isEqualTo("Creator-edited opening"));
        assertThat(response.getScriptJson().getRevisionAudit()).containsEntry("revisionNumber", 3);
        assertThat(response.getScriptJson().getCharacters()).isEmpty();
        assertThat(storyIdea.getSelectionContext()).extractingByKey("storyScript")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("storyline", "Keep this exact creator-edited product storyline in the database.")
                .containsKey("revisionAudit");
        verify(projectService).markSelectedIdea(storyIdea);
    }

    @Test
    void convertsDialogueAndBlockingToProductOnlyScreenplay() {
        IdeaService service = service();
        GeneratedScriptResponse.CinematicShot shot = GeneratedScriptResponse.CinematicShot.builder()
                .shotNumber(1)
                .title("Priya tries the snack")
                .purpose("Show the benefit")
                .peopleInFrame(1)
                .primaryActors(List.of("Priya"))
                .primaryCharacters(List.of("Priya"))
                .dialogue(Map.of("Priya", "It tastes indulgent without derailing my routine."))
                .action("Priya lifts the pack.")
                .setDesign("Kitchen with Priya at the counter.")
                .assetGenerationPrompt("A customer holding the product")
                .seedancePrompt("Priya holds the pack")
                .build();
        GeneratedScriptResponse.CinematicScript screenplay = GeneratedScriptResponse.CinematicScript.builder()
                .shots(List.of(shot))
                .build();

        service.applyNoHumanProductScreenplayContract(screenplay);

        assertThat(screenplay.getExtra()).containsEntry("noHumans", true);
        assertThat(shot.getPeopleInFrame()).isZero();
        assertThat(shot.getPrimaryActors()).isEmpty();
        assertThat(shot.getPrimaryCharacters()).isEmpty();
        assertThat(shot.getDialogue()).isEmpty();
        assertThat(shot.getVoiceOver()).isEqualTo("It tastes indulgent without derailing my routine.");
        assertThat(shot.getAction()).contains("canonical product").doesNotContain("Priya");
        assertThat(shot.getAssetGenerationPrompt()).contains("no people", "no hands");
        assertThat(shot.getSeedancePrompt()).contains("no people", "no hands");
        assertThat(shot.getExtra())
                .containsEntry("noHumans", true)
                .containsEntry("speakerVisibility", "OFF_SCREEN")
                .containsEntry("lipSyncRequired", false);
    }

    private Map<String, Object> productOnlyBrief() {
        return Map.of(
                "briefMode", "product_ad_agent",
                "productIntelligenceBrief", Map.of(
                        "productName", "Double Chocolate Peanut Butter Bar",
                        "cta", "Try it today",
                        "noHumans", true,
                        "productUnderstanding", Map.of(
                                "productName", "Double Chocolate Peanut Butter Bar",
                                "productCategory", "snack bar",
                                "ingredients", List.of("chocolate", "peanut butter"),
                                "benefits", List.of("rich texture", "satisfying crunch"),
                                "usp", "indulgent taste with a balanced positioning"
                        )
                )
        );
    }

    private IdeaService service() {
        return new IdeaService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper()
        );
    }
}
