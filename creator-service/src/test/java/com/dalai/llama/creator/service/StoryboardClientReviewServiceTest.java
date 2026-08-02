package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.dto.request.StoryboardClientReviewChatRequest;
import com.dalai.llama.creator.dto.request.StoryboardClientReviewRequest;
import com.dalai.llama.creator.dto.response.StoryboardClientReviewResponse;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class StoryboardClientReviewServiceTest {

    @Mock
    private CreatorScriptRepository scriptRepository;
    @Mock
    private CreatorAssetRepository assetRepository;
    @Mock
    private AssetStorageService assetStorageService;
    @Mock
    private CreatorScriptShotRepository scriptShotRepository;
    @Mock
    private CreatorScriptShotPlanRepository shotPlanRepository;
    @Mock
    private CreatorAiService creatorAiService;
    @Mock
    private CreatorCreativeLearningService creativeLearningService;

    private StoryboardClientReviewService service;
    private CreatorScript script;

    @BeforeEach
    void setUp() {
        service = new StoryboardClientReviewService(
                scriptRepository,
                assetRepository,
                assetStorageService,
                scriptShotRepository,
                shotPlanRepository,
                creatorAiService,
                creativeLearningService
        );
        script = CreatorScript.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-1")
                .userId("user-1")
                .title("Summer Launch")
                .durationSeconds(30)
                .dialogueLanguage("Hinglish")
                .screenType("vertical")
                .status("GENERATED")
                .scriptPayload(new LinkedHashMap<>(Map.of("storyline", "A founder reveals the product.")))
                .shots(List.of(new LinkedHashMap<>(Map.of(
                        "shotNumber", 1,
                        "title", "The reveal",
                        "action", "The founder lifts the product into warm window light.",
                        "dialogue", Map.of("founder", "This changed everything."),
                        "durationSeconds", 4
                ))))
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        when(scriptRepository.findByIdAndTenantIdAndUserId(script.getId(), "tenant-1", "user-1"))
                .thenReturn(Optional.of(script));
        when(scriptRepository.save(any(CreatorScript.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(creativeLearningService.recordApprovedReview(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("status", "NO_GENERALIZABLE_RULES", "approvedRuleCount", 0));
    }

    @Test
    void saveReviewPersistsWorkingCopyWithoutMutatingAppliedPlanning() {
        StoryboardClientReviewResponse response = service.saveReview(
                script.getId(),
                new StoryboardClientReviewRequest(
                        "Hold the opening reaction.",
                        "Use the approved amber pack shot.",
                        "Keep the delivery conversational.",
                        "Hindi",
                        "CHANGES_REQUESTED",
                        List.of(),
                        List.of("https://approved.example/product"),
                        List.of(),
                        List.of(Map.of(
                                "id", "review-1",
                                "role", "user",
                                "targetType", "STORYBOARD",
                                "shotNumber", 1,
                                "status", "PENDING",
                                "text", "Use a tighter product reveal."
                        )),
                        Map.of(),
                        List.of()
                ),
                "tenant-1",
                "user-1"
        );

        assertThat(response.dialogueLanguage()).isEqualTo("Hindi");
        assertThat(response.reviewStatus()).isEqualTo("CHANGES_REQUESTED");
        assertThat(script.getDialogueLanguage()).isEqualTo("Hinglish");
        assertThat(script.getScriptPayload()).doesNotContainKey("dialogueLanguage");
        assertThat(((Map<?, ?>) script.getScriptPayload().get("clientReview")).get("dialogueLanguage"))
                .isEqualTo("Hindi");
        assertThat(response.reviewChat()).singleElement().satisfies(message ->
                assertThat(message).containsEntry("text", "Use a tighter product reveal.")
        );
        assertThat(((Map<?, ?>) script.getScriptPayload().get("clientReview")).get("productionFramesFeedback"))
                .isEqualTo("Use the approved amber pack shot.");
        verify(scriptRepository).save(script);
    }

    @Test
    void saveReviewBoundsHistoryAndCompactsOldProposals() {
        List<Map<String, Object>> history = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            List<Map<String, Object>> revisions = new ArrayList<>();
            for (int revision = 0; revision < 20; revision++) {
                revisions.add(Map.of(
                        "shotNumber", revision + 1,
                        "imageRevisionPrompt", "x".repeat(5_000)
                ));
            }
            history.add(Map.of(
                    "id", "message-" + index,
                    "role", "assistant",
                    "status", "COMPLETED",
                    "text", "Review response " + index,
                    "proposal", Map.of(
                            "changeSummary", "Summary " + index,
                            "imageRevisionPrompt", "y".repeat(5_000),
                            "shotRevisions", revisions
                    )
            ));
        }

        StoryboardClientReviewResponse response = service.saveReview(
                script.getId(),
                new StoryboardClientReviewRequest(
                        null,
                        null,
                        null,
                        "Hindi",
                        "CHANGES_REQUESTED",
                        List.of(),
                        List.of(),
                        List.of(),
                        history,
                        Map.of(),
                        List.of()
                ),
                "tenant-1",
                "user-1"
        );

        assertThat(response.reviewChat()).hasSize(24);
        assertThat(response.reviewChat().get(0)).containsEntry("id", "message-6");
        Map<String, Object> oldestProposal = (Map<String, Object>) response.reviewChat().get(0).get("proposal");
        assertThat(oldestProposal)
                .containsEntry("detailsCompacted", true)
                .doesNotContainKeys("imageRevisionPrompt", "shotRevisions");
        Map<String, Object> newestProposal = (Map<String, Object>) response.reviewChat().get(23).get("proposal");
        assertThat((String) newestProposal.get("imageRevisionPrompt")).hasSize(4_000);
        assertThat((List<?>) newestProposal.get("shotRevisions")).hasSize(12);
        Map<String, Object> storedReview = (Map<String, Object>) script.getScriptPayload().get("clientReview");
        assertThat((List<Map<String, Object>>) storedReview.get("conversationMemory"))
                .hasSize(6)
                .extracting(item -> item.get("id"))
                .containsExactly(
                        "message-0", "message-1", "message-2",
                        "message-3", "message-4", "message-5"
                );
    }

    @Test
    void uploadFontReferencePersistsTypographyOnlyAsset() {
        UUID assetId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "client-font-sample.png",
                "image/png",
                new byte[]{1, 2, 3}
        );
        when(assetStorageService.uploadCreatorAsset(any(), any(), eq("image/png"), any(Duration.class)))
                .thenReturn(new AssetStorageService.StoredObject(
                        "creator-assets",
                        "screenplay-videos/font-references/sample.png",
                        "image/png",
                        3L,
                        "https://assets.example/font-sample.png"
                ));
        when(assetRepository.saveAndFlush(any(CreatorAsset.class))).thenAnswer(invocation -> {
            CreatorAsset saved = invocation.getArgument(0);
            saved.setId(assetId);
            return saved;
        });
        when(assetStorageService.signedUrl(eq("creator-assets"), any(), any(Duration.class)))
                .thenReturn("https://assets.example/font-sample.png");

        Map<String, Object> uploaded = service.uploadFontReferenceImage(
                script.getId(),
                file,
                "tenant-1",
                "user-1"
        );

        assertThat(uploaded)
                .containsEntry("assetId", assetId.toString())
                .containsEntry("referenceRole", "typography_style_reference")
                .containsEntry("originalFilename", "client-font-sample.png");
        Map<?, ?> review = (Map<?, ?>) script.getScriptPayload().get("clientReview");
        assertThat((List<?>) review.get("fontReferenceImages")).hasSize(1);
    }

    @Test
    void applyReviewPropagatesAiTypographyOverlayAndContentRules() {
        UUID fontAssetId = UUID.randomUUID();
        CreatorAsset fontAsset = CreatorAsset.builder()
                .id(fontAssetId)
                .tenantId("tenant-1")
                .userId("user-1")
                .assetType("STORYBOARD_FONT_REFERENCE_IMAGE")
                .bucket("creator-assets")
                .objectKey("screenplay-videos/font-references/client-sample.png")
                .contentType("image/png")
                .sizeBytes(3L)
                .metadata(new LinkedHashMap<>(Map.of(
                        "scriptId", script.getId().toString(),
                        "originalFilename", "client-sample.png"
                )))
                .build();
        when(assetRepository.findById(fontAssetId)).thenReturn(Optional.of(fontAsset));
        when(assetStorageService.signedUrl(eq("creator-assets"), eq("screenplay-videos/font-references/client-sample.png"), any()))
                .thenReturn("https://assets.example/client-font.png");

        Map<String, Object> overlay = new LinkedHashMap<>();
        overlay.put("shotNumber", 1);
        overlay.put("enabled", true);
        overlay.put("text", "See the difference");
        overlay.put("fontFamily", "Montserrat");
        overlay.put("fontWeight", 800);
        overlay.put("entrance", "Wipe and fade");
        overlay.put("entranceDurationMs", 520);
        overlay.put("speed", "Quick");
        overlay.put("position", "Upper safe zone");

        Map<String, Object> revisedShot = new LinkedHashMap<>(script.getShots().get(0));
        revisedShot.put("action", "Break the finished chocolate piece in a clean macro detail.");
        revisedShot.put("dialogue", Map.of("founder", "This is the detail that matters."));
        revisedShot.put("dialogueLanguage", "English");

        Map<String, Object> aiOutput = new LinkedHashMap<>();
        aiOutput.put("storyline", "A founder reveals the finished product through distinct detail beats.");
        aiOutput.put("screenplay", "English screenplay with a detailed product reveal.");
        aiOutput.put("typographySystem", Map.of(
                "primaryFont", "Montserrat",
                "primaryWeight", 800,
                "secondaryFont", "Inter",
                "secondaryWeight", 600
        ));
        aiOutput.put("overlayPlan", List.of(overlay));
        aiOutput.put("shots", List.of(revisedShot));
        aiOutput.put("dialogueLocalization", Map.of(
                "translationApplied", true,
                "sourceLanguage", "Hinglish",
                "targetLanguage", "English",
                "translatedShotNumbers", List.of(1)
        ));

        ArgumentCaptor<Map<String, Object>> providerInput = ArgumentCaptor.forClass(Map.class);
        when(creatorAiService.generateMetered(eq("CLIENT_FEEDBACK_PROPAGATE"), providerInput.capture(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        aiOutput,
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));
        when(scriptShotRepository.findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(script.getId()))
                .thenReturn(List.of());
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId()))
                .thenReturn(List.of());

        StoryboardClientReviewResponse response = service.applyReview(
                script.getId(),
                new StoryboardClientReviewRequest(
                        null,
                        null,
                        null,
                        "English",
                        "CHANGES_REQUESTED",
                        List.of(),
                        List.of("https://approved.example/product"),
                        List.of(Map.of("assetId", fontAssetId.toString())),
                        Map.of(),
                        List.of()
                ),
                "tenant-1",
                "user-1"
        );

        assertThat(response.propagation()).containsEntry("status", "APPLIED");
        assertThat(response.overlayPlan()).hasSize(1);
        assertThat(response.typographySystem()).containsEntry("primaryFont", "Montserrat");
        assertThat(script.getDialogueLanguage()).isEqualTo("English");
        assertThat(script.getShots().get(0)).containsEntry("detailLevel", "production_ready");
        assertThat(script.getScriptPayload()).containsKeys("creativeDirection", "planningPropagation", "contentRules");
        assertThat(response.fontReferenceImages()).hasSize(1);
        assertThat(response.typographySystem())
                .containsEntry("fontReferenceImageCount", 1)
                .containsEntry("approximation", true);
        assertThat(response.videoDirectorPlan().get("perSecondVideoPrompt").toString())
                .contains("[0.0-1.0s]")
                .contains("Shot 1")
                .contains("Camera:")
                .contains("Lighting:");
        assertThat(response.videoDirectorPlan().get("masterVideoPrompt").toString())
                .contains("[SECOND-BY-SECOND COMPLETE AD EXECUTION]");
        assertThat(providerInput.getValue()).containsEntry("attachReferenceImages", true);
        assertThat(providerInput.getValue()).containsEntry("sourceDialogueLanguage", "Hinglish");
        assertThat((Map<String, Object>) providerInput.getValue().get("billingPolicy"))
                .containsEntry("reviewRound", 1)
                .containsEntry("includedReviewRoundLimit", 2)
                .containsEntry("includedInPackage", true);
        assertThat((Map<String, Object>) script.getScriptPayload().get("clientReview"))
                .containsEntry("appliedReviewCount", 1)
                .containsEntry("lastAppliedReviewIncludedInPackage", true);
        assertThat(providerInput.getValue().get("referenceImageUrls"))
                .isEqualTo(List.of("https://assets.example/client-font.png"));
    }

    @Test
    void applyReviewRejectsMetadataOnlyDialogueLanguageChange() {
        Map<String, Object> metadataOnlyShot = new LinkedHashMap<>(script.getShots().get(0));
        metadataOnlyShot.put("dialogueLanguage", "English");
        Map<String, Object> metadataOnlyOutput = new LinkedHashMap<>();
        metadataOnlyOutput.put("shots", List.of(metadataOnlyShot));
        metadataOnlyOutput.put("dialogueLocalization", Map.of(
                "translationApplied", true,
                "sourceLanguage", "Hinglish",
                "targetLanguage", "English",
                "translatedShotNumbers", List.of(1)
        ));
        when(creatorAiService.generateMetered(eq("CLIENT_FEEDBACK_PROPAGATE"), any(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        metadataOnlyOutput,
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.applyReview(
                script.getId(),
                new StoryboardClientReviewRequest(
                        null,
                        null,
                        null,
                        "English",
                        "CHANGES_REQUESTED",
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of()
                ),
                "tenant-1",
                "user-1"
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));

        assertThat(script.getDialogueLanguage()).isEqualTo("Hinglish");
        assertThat(script.getShots().get(0).get("dialogue").toString())
                .contains("This changed everything.");
        verify(creatorAiService).publishBillingDebit(eq("CLIENT_FEEDBACK_PROPAGATE"), any(), any());
    }

    @Test
    void applyUsesOnlyReferencesAttachedToTheActiveMessageAndKeepsHistory() {
        Map<String, Object> payloadWithTwoAppliedReviews = new LinkedHashMap<>(script.getScriptPayload());
        Map<String, Object> reviewWithTwoAppliedRounds = new LinkedHashMap<>((Map<String, Object>)
                payloadWithTwoAppliedReviews.getOrDefault("clientReview", Map.of()));
        reviewWithTwoAppliedRounds.put("appliedReviewCount", 2);
        payloadWithTwoAppliedReviews.put("clientReview", reviewWithTwoAppliedRounds);
        script.setScriptPayload(payloadWithTwoAppliedReviews);
        UUID historicalAssetId = UUID.randomUUID();
        UUID activeAssetId = UUID.randomUUID();
        CreatorAsset historicalAsset = visualReferenceAsset(
                historicalAssetId,
                "client-review/history.jpg"
        );
        CreatorAsset activeAsset = visualReferenceAsset(
                activeAssetId,
                "client-review/active.jpg"
        );
        when(assetRepository.findById(historicalAssetId)).thenReturn(Optional.of(historicalAsset));
        when(assetRepository.findById(activeAssetId)).thenReturn(Optional.of(activeAsset));
        when(assetStorageService.signedUrl(eq("creator-assets"), eq("client-review/history.jpg"), any()))
                .thenReturn("https://assets.example/history.jpg");
        when(assetStorageService.signedUrl(eq("creator-assets"), eq("client-review/active.jpg"), any()))
                .thenReturn("https://assets.example/active.jpg");
        when(scriptShotRepository.findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(script.getId()))
                .thenReturn(List.of());
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId()))
                .thenReturn(List.of());
        ArgumentCaptor<Map<String, Object>> providerInput = ArgumentCaptor.forClass(Map.class);
        when(creatorAiService.generateMetered(eq("CLIENT_FEEDBACK_PROPAGATE"), providerInput.capture(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        Map.of("shots", script.getShots()),
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));

        StoryboardClientReviewResponse response = service.applyReview(
                script.getId(),
                new StoryboardClientReviewRequest(
                        null,
                        null,
                        null,
                        "Hinglish",
                        "CHANGES_REQUESTED",
                        List.of(),
                        List.of(),
                        List.of(
                                Map.of("assetId", historicalAssetId.toString()),
                                Map.of("assetId", activeAssetId.toString())
                        ),
                        List.of(),
                        List.of(
                                Map.of(
                                        "id", "old-review",
                                        "role", "user",
                                        "targetType", "STORYBOARD_AND_PRODUCT",
                                        "shotNumber", 1,
                                        "status", "COMPLETED",
                                        "text", "Earlier reference.",
                                        "visualReferenceAssetIds", List.of(historicalAssetId.toString())
                                ),
                                Map.of(
                                        "id", "active-review",
                                        "role", "user",
                                        "targetType", "STORYBOARD_AND_PRODUCT",
                                        "shotNumber", 1,
                                        "status", "AWAITING_CONFIRMATION",
                                        "text", "Use this reference for the current change.",
                                        "visualReferenceAssetIds", List.of(activeAssetId.toString())
                                )
                        ),
                        Map.of(),
                        List.of(),
                        Map.of()
                ),
                "tenant-1",
                "user-1"
        );

        assertThat((Map<String, Object>) providerInput.getValue().get("billingPolicy"))
                .containsEntry("reviewRound", 3)
                .containsEntry("includedInPackage", false);
        assertThat((Map<String, Object>) script.getScriptPayload().get("clientReview"))
                .containsEntry("appliedReviewCount", 3)
                .containsEntry("lastAppliedReviewIncludedInPackage", false);

        assertThat(response.visualReferenceImages()).hasSize(2);
        assertThat(providerInput.getValue().get("referenceImageUrls"))
                .isEqualTo(List.of("https://assets.example/active.jpg"));
        assertThat((List<?>) providerInput.getValue().get("visualReferenceImages"))
                .singleElement()
                .satisfies(item -> assertThat((Map<String, Object>) item)
                        .containsEntry("assetId", activeAssetId.toString()));
        Map<String, Object> providerReview =
                (Map<String, Object>) providerInput.getValue().get("clientReview");
        assertThat((List<?>) providerReview.get("visualReferenceImages")).hasSize(1);
        assertThat((List<?>) providerReview.get("reviewChat")).singleElement();
        assertThat(providerInput.getValue()).containsEntry("visualReferenceHistoryCount", 2);
    }

    @Test
    void chatAsksToConfirmSelectedDialogueLanguageAndRetainsOlderContext() {
        List<Map<String, Object>> history = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            history.add(Map.of(
                    "id", "context-" + index,
                    "role", index % 2 == 0 ? "user" : "assistant",
                    "status", "COMPLETED",
                    "text", "Earlier client decision " + index
            ));
        }
        service.saveReview(
                script.getId(),
                new StoryboardClientReviewRequest(
                        null,
                        null,
                        null,
                        "Hindi",
                        "CHANGES_REQUESTED",
                        List.of(),
                        List.of(),
                        List.of(),
                        history,
                        Map.of(),
                        List.of()
                ),
                "tenant-1",
                "user-1"
        );
        when(assetRepository.findShotImageAssetsForScript(script.getId(), "tenant-1", "user-1"))
                .thenReturn(List.of());
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId()))
                .thenReturn(List.of());
        ArgumentCaptor<Map<String, Object>> providerInput = ArgumentCaptor.forClass(Map.class);
        when(creatorAiService.generateMetered(eq("CLIENT_REVIEW_RAG_CHAT"), providerInput.capture(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        Map.of(
                                "assistantMessage", "I reviewed the current planning context.",
                                "changeSummary", "Keep the current plan ready for client confirmation.",
                                "affectedShotNumbers", List.of(1),
                                "requiresFrameRegeneration", false
                        ),
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));

        Map<String, Object> response = service.chatReview(
                script.getId(),
                new StoryboardClientReviewChatRequest(
                        "language-confirmation",
                        "Please review the current plan.",
                        "PLANNING",
                        null,
                        "Hindi",
                        Map.of()
                ),
                "tenant-1",
                "user-1"
        );

        Map<String, Object> ragContext = (Map<String, Object>) providerInput.getValue().get("ragContext");
        assertThat((Map<String, Object>) ragContext.get("dialogueLanguageChange"))
                .containsEntry("pending", true)
                .containsEntry("appliedLanguage", "Hinglish")
                .containsEntry("selectedLanguage", "Hindi");
        assertThat((List<Map<String, Object>>) ragContext.get("reviewHistory"))
                .hasSize(8)
                .first()
                .satisfies(message -> assertThat(message).containsEntry("id", "context-23"));
        assertThat((List<Map<String, Object>>) ragContext.get("conversationMemory"))
                .isNotEmpty()
                .anySatisfy(message -> assertThat(message).containsEntry("id", "context-0"));
        Map<String, Object> assistantMessage = (Map<String, Object>) response.get("assistantMessage");
        assertThat(assistantMessage)
                .containsEntry("languageChangePrompt", true)
                .containsEntry("sourceDialogueLanguage", "Hinglish")
                .containsEntry("targetDialogueLanguage", "Hindi");
        assertThat(assistantMessage.get("text").toString())
                .contains("You selected Hindi")
                .contains("currently applied dialogue language is Hinglish")
                .contains("Do you want to translate");
        assertThat(script.getDialogueLanguage()).isEqualTo("Hinglish");
        assertThat(script.getScriptPayload()).doesNotContainKey("dialogueLanguage");
        assertThat(((StoryboardClientReviewResponse) response.get("review")).dialogueLanguage())
                .isEqualTo("Hindi");
    }

    @Test
    void chatReviewRetrievesAdjacentShotsAndAttachesCurrentFrames() {
        script.setShots(List.of(
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 1,
                        "title", "Opening",
                        "action", "The sealed pack enters frame."
                )),
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 2,
                        "title", "Product detail",
                        "action", "The finished bar snaps in macro."
                )),
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 3,
                        "title", "Hero close",
                        "action", "The pack settles into hero light."
                ))
        ));
        CreatorAsset storyboardFrame = CreatorAsset.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-1")
                .userId("user-1")
                .assetType("STORYBOARD_IMAGE")
                .bucket("creator-assets")
                .objectKey("storyboards/shot-2.jpg")
                .metadata(new LinkedHashMap<>(Map.of("shotNumber", 2, "imageKind", "storyboard")))
                .createdAt(OffsetDateTime.now())
                .build();
        CreatorAsset productFrame = CreatorAsset.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-1")
                .userId("user-1")
                .assetType("PRODUCTION_IMAGE")
                .bucket("creator-assets")
                .objectKey("production/shot-2.jpg")
                .metadata(new LinkedHashMap<>(Map.of("shotNumber", 2, "imageKind", "production")))
                .createdAt(OffsetDateTime.now())
                .build();
        UUID inspirationId = UUID.randomUUID();
        CreatorAsset inspiration = CreatorAsset.builder()
                .id(inspirationId)
                .tenantId("tenant-1")
                .userId("user-1")
                .assetType("STORYBOARD_VISUAL_REFERENCE_IMAGE")
                .bucket("creator-assets")
                .objectKey("client-review/inspiration.jpg")
                .metadata(new LinkedHashMap<>(Map.of(
                        "scriptId", script.getId().toString(),
                        "originalFilename", "inspiration.jpg"
                )))
                .createdAt(OffsetDateTime.now())
                .build();
        when(assetRepository.findShotImageAssetsForScript(script.getId(), "tenant-1", "user-1"))
                .thenReturn(List.of(storyboardFrame, productFrame));
        when(assetRepository.findById(inspirationId)).thenReturn(Optional.of(inspiration));
        when(assetStorageService.signedUrl(eq("creator-assets"), eq("storyboards/shot-2.jpg"), any()))
                .thenReturn("https://assets.example/storyboard-2.jpg");
        when(assetStorageService.signedUrl(eq("creator-assets"), eq("production/shot-2.jpg"), any()))
                .thenReturn("https://assets.example/product-2.jpg");
        when(assetStorageService.signedUrl(eq("creator-assets"), eq("client-review/inspiration.jpg"), any()))
                .thenReturn("https://assets.example/inspiration.jpg");
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())).thenReturn(List.of());

        ArgumentCaptor<Map<String, Object>> providerInput = ArgumentCaptor.forClass(Map.class);
        when(creatorAiService.generateMetered(eq("CLIENT_REVIEW_RAG_CHAT"), providerInput.capture(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        Map.of(
                                "assistantMessage", "I can see the current macro snap and will replace it with a clean texture reveal.",
                                "changeSummary", "Replace the macro snap.",
                                "imageRevisionPrompt", "Show the finished chocolate texture without pouring.",
                                "proposedShot", Map.of("shotNumber", 2, "action", "Reveal the finished texture."),
                                "storyboardChangeRequired", false,
                                "productFrameChangeRequired", true,
                                "requiresFrameRegeneration", true,
                                "attachedReferenceImageCount", 3
                        ),
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));

        Map<String, Object> response = service.chatReview(
                script.getId(),
                new StoryboardClientReviewChatRequest(
                        "review-shot-2",
                        "Change this repetitive shot.",
                        "STORYBOARD_AND_PRODUCT",
                        2,
                        "English",
                        Map.of(
                                "reviewChat", List.of(),
                                "visualReferenceImages", List.of(Map.of("assetId", inspirationId.toString()))
                        ),
                        List.of(inspirationId.toString()),
                        "EXACT_SOURCE"
                ),
                "tenant-1",
                "user-1"
        );

        Map<String, Object> ragContext = (Map<String, Object>) providerInput.getValue().get("ragContext");
        Map<String, Object> selectedShot = (Map<String, Object>) ragContext.get("selectedShot");
        Map<String, Object> previousShot = (Map<String, Object>) ragContext.get("previousShot");
        Map<String, Object> nextShot = (Map<String, Object>) ragContext.get("nextShot");
        Map<String, Object> currentImages = (Map<String, Object>) ragContext.get("currentImages");
        List<Map<String, Object>> allShots = (List<Map<String, Object>>) ragContext.get("allShots");
        List<Map<String, Object>> allShotImages = (List<Map<String, Object>>) ragContext.get("allShotImages");
        assertThat(selectedShot).containsEntry("shotNumber", 2);
        assertThat(previousShot).containsEntry("shotNumber", 1);
        assertThat(nextShot).containsEntry("shotNumber", 3);
        assertThat(allShots).extracting(item -> item.get("shotNumber")).containsExactly(1, 2, 3);
        assertThat(allShotImages).extracting(item -> item.get("shotNumber")).containsExactly(1, 2, 3);
        assertThat(currentImages)
                .containsEntry("storyboardImageUrl", "https://assets.example/storyboard-2.jpg")
                .containsEntry("productionImageUrl", "https://assets.example/product-2.jpg");
        assertThat(providerInput.getValue())
                .containsEntry("attachReferenceImages", true)
                .containsEntry("visualReferenceUsageMode", "EXACT_SOURCE")
                .containsEntry(
                        "referenceImageUrls",
                        List.of(
                                "https://assets.example/storyboard-2.jpg",
                                "https://assets.example/product-2.jpg",
                                "https://assets.example/inspiration.jpg"
                        )
                );
        List<Map<String, Object>> referenceAssets =
                (List<Map<String, Object>>) providerInput.getValue().get("referenceImageAssets");
        assertThat(referenceAssets)
                .anySatisfy(asset -> assertThat(asset)
                        .containsEntry("assetId", inspirationId.toString())
                        .containsEntry("usageMode", "EXACT_SOURCE")
                        .containsEntry("referenceRole", "exact_visual_source"));
        assertThat(response)
                .containsEntry("status", "ANALYZED")
                .containsEntry("attachedImageCount", 3);
        assertThat((Map<String, Object>) response.get("userMessage"))
                .containsEntry("visualReferenceAssetIds", List.of(inspirationId.toString()))
                .containsEntry("visualReferenceUsageMode", "EXACT_SOURCE")
                .containsEntry("status", "AWAITING_CONFIRMATION");
        Map<String, Object> assistantMessage = (Map<String, Object>) response.get("assistantMessage");
        assertThat(assistantMessage)
                .containsEntry("targetType", "STORYBOARD_AND_PRODUCT")
                .containsEntry("affectedShotNumbers", List.of(2))
                .containsEntry("confirmationForMessageId", "review-shot-2");
        assertThat(assistantMessage.get("text").toString())
                .contains("Shot 2's storyboard and product frame")
                .contains("final per-second video prompt");
        Map<String, Object> proposal = (Map<String, Object>) response.get("proposal");
        List<Map<String, Object>> planningPreview =
                (List<Map<String, Object>>) proposal.get("planningChangePreview");
        assertThat(planningPreview).hasSize(1);
        assertThat(planningPreview.get(0))
                .containsEntry("shotNumber", 2)
                .containsEntry("storyboardChangeRequired", true)
                .containsEntry("productFrameChangeRequired", true)
                .containsKeys(
                        "currentFrameDescription",
                        "proposedFrameDescription",
                        "cameraPlan",
                        "lensFocusPlan",
                        "lightingPlan",
                        "directionPlan",
                        "transitionPlan",
                        "perSecondFrames"
                );
        assertThat((List<?>) planningPreview.get(0).get("perSecondFrames")).isNotEmpty();
        assertThat(((StoryboardClientReviewResponse) response.get("review")).reviewChat()).hasSize(2);
    }

    @Test
    void freeFormChatUnderstandsMultipleShotsWithoutASelectedFrame() {
        script.setShots(List.of(
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 1,
                        "title", "Brand world",
                        "action", "An amber edge discovers the monolith."
                )),
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 2,
                        "title", "Texture reveal",
                        "action", "Macro texture emerges from the same amber light."
                )),
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 3,
                        "title", "Hero product",
                        "action", "The approved pack resolves in full."
                ))
        ));
        when(assetRepository.findShotImageAssetsForScript(script.getId(), "tenant-1", "user-1"))
                .thenReturn(List.of());
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())).thenReturn(List.of());
        when(creatorAiService.generateMetered(eq("CLIENT_REVIEW_RAG_CHAT"), any(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        Map.of(
                                "assistantMessage", "Shot 1 currently ends in amber shadow while Shot 2 begins on a separate texture beat. I will connect them through one continuous light sweep.",
                                "changeSummary", "Create one motivated transition from Shot 1 to Shot 2.",
                                "affectedShotNumbers", List.of(1, 2),
                                "shotRevisions", List.of(
                                        Map.of(
                                                "shotNumber", 1,
                                                "changeSummary", "End on the amber sweep.",
                                                "imageRevisionPrompt", "End Shot 1 with an amber light sweep moving screen-left to screen-right."
                                        ),
                                        Map.of(
                                                "shotNumber", 2,
                                                "changeSummary", "Continue the amber sweep.",
                                                "imageRevisionPrompt", "Open Shot 2 with the same amber sweep revealing macro texture."
                                        )
                                ),
                                "requiresFrameRegeneration", true
                        ),
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));

        Map<String, Object> response = service.chatReview(
                script.getId(),
                new StoryboardClientReviewChatRequest(
                        "review-cross-shot",
                        "Make Shot 1 flow into Shot 2 using the same light direction.",
                        "PLANNING",
                        null,
                        "English",
                        Map.of("reviewChat", List.of())
                ),
                "tenant-1",
                "user-1"
        );

        Map<String, Object> proposal = (Map<String, Object>) response.get("proposal");
        assertThat(proposal)
                .containsEntry("targetType", "STORYBOARD_AND_PRODUCT")
                .containsEntry("affectedShotNumbers", List.of(1, 2))
                .containsEntry("requiresFrameRegeneration", true);
        assertThat((List<?>) proposal.get("shotRevisions")).hasSize(2);
        List<Map<String, Object>> planningPreview =
                (List<Map<String, Object>>) proposal.get("planningChangePreview");
        assertThat(planningPreview).hasSize(2);
        assertThat(planningPreview)
                .allSatisfy(item -> {
                    assertThat(item.get("cameraPlan").toString()).isNotBlank();
                    assertThat(item.get("lightingPlan").toString()).isNotBlank();
                    assertThat((List<?>) item.get("perSecondFrames")).isNotEmpty();
                    assertThat((Map<String, Object>) item.get("premiumStandards"))
                            .containsKeys("capture", "cameraDepartment", "lightingDepartment", "direction");
                });
        assertThat((Map<String, Object>) response.get("userMessage"))
                .containsEntry("affectedShotNumbers", List.of(1, 2));
        assertThat((Map<String, Object>) response.get("assistantMessage"))
                .containsEntry("affectedShotNumbers", List.of(1, 2));
    }

    @Test
    void incompleteProviderOutputBuildsDistinctGroundedShotPreview() {
        script.setShots(List.of(
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 1,
                        "title", "Old opening",
                        "action", "A static pack sits on a black table."
                )),
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 2,
                        "title", "Old texture",
                        "action", "Chocolate pours into the same composition."
                )),
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 3,
                        "title", "Ingredient reveal",
                        "action", "Cocoa ingredients enter the frame."
                ))
        ));
        when(assetRepository.findShotImageAssetsForScript(script.getId(), "tenant-1", "user-1"))
                .thenReturn(List.of());
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())).thenReturn(List.of());
        when(creatorAiService.generateMetered(eq("CLIENT_REVIEW_RAG_CHAT"), any(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        Map.of(
                                "provider", "gemini",
                                "finishReason", "MAX_TOKENS"
                        ),
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));

        Map<String, Object> response = service.chatReview(
                script.getId(),
                new StoryboardClientReviewChatRequest(
                        "review-incomplete-provider",
                        "Revise shots 1 and 2: Shot 1: replace the static pack with an obsidian monolith emerging from amber darkness. "
                                + "Shot 2: use the image language of Shot 3 as the continuity anchor, then continue the same amber sweep into a macro engraved chocolate texture; the current product frame is good, keep product frame.",
                        "PLANNING",
                        null,
                        "English",
                        Map.of("reviewChat", List.of())
                ),
                "tenant-1",
                "user-1"
        );

        Map<String, Object> proposal = (Map<String, Object>) response.get("proposal");
        assertThat(proposal)
                .containsEntry("analysisSource", "GROUNDED_FALLBACK")
                .containsEntry("affectedShotNumbers", List.of(1, 2));
        List<Map<String, Object>> preview = (List<Map<String, Object>>) proposal.get("planningChangePreview");
        assertThat(preview).hasSize(2).allSatisfy(item -> {
            assertThat(item.get("currentFrameDescription").toString())
                    .isNotEqualTo(item.get("proposedFrameDescription").toString());
            assertThat(item.get("cameraPlan").toString()).isNotBlank();
            assertThat(item.get("lightingPlan").toString()).isNotBlank();
            assertThat((List<?>) item.get("perSecondFrames")).isNotEmpty();
        });
        assertThat(preview.get(0))
                .containsEntry("storyboardChangeRequired", true)
                .containsEntry("productFrameChangeRequired", true);
        assertThat(preview.get(1))
                .containsEntry("storyboardChangeRequired", true)
                .containsEntry("productFrameChangeRequired", false);
        assertThat(preview.get(1).get("proposedFrameDescription").toString()).contains("Shot 3");
    }

    @Test
    void longDirectorBriefIsPreservedForProjectInspection() {
        script.setShots(List.of(
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 1,
                        "title", "Brand world",
                        "action", "Amber light discovers a chocolate form."
                )),
                new LinkedHashMap<>(Map.of(
                        "shotNumber", 2,
                        "title", "Texture reveal",
                        "action", "The camera enters a sculptural chocolate ribbon."
                ))
        ));
        String longBrief = "Review Shot 1 and Shot 2 comprehensively. "
                + "Preserve premium chocolate identity, motivated continuity, camera, lighting, and edit direction. "
                + "Director note: reveal mystery first, then transform the same chocolate material into the texture sequence. ".repeat(85);
        assertThat(longBrief.length()).isBetween(4_001, 24_000);
        when(assetRepository.findShotImageAssetsForScript(script.getId(), "tenant-1", "user-1"))
                .thenReturn(List.of());
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())).thenReturn(List.of());
        ArgumentCaptor<Map<String, Object>> providerInput = ArgumentCaptor.forClass(Map.class);
        when(creatorAiService.generateMetered(eq("CLIENT_REVIEW_RAG_CHAT"), providerInput.capture(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        Map.of(
                                "assistantMessage", "I inspected both shots and prepared one coherent premium transition.",
                                "changeSummary", "Replace the stale opening with a mystery-first chocolate transformation.",
                                "affectedShotNumbers", List.of(1, 2),
                                "requiresFrameRegeneration", true
                        ),
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));

        service.chatReview(
                script.getId(),
                new StoryboardClientReviewChatRequest(
                        "review-long-brief",
                        longBrief,
                        "PLANNING",
                        null,
                        "English",
                        Map.of(
                                "storyboardFeedback", "Preserve the approved story arc.",
                                "dialogueLanguage", "English"
                        )
                ),
                "tenant-1",
                "user-1"
        );

        assertThat(providerInput.getValue().get("message")).isEqualTo(longBrief.trim());
    }

    @Test
    void explicitShotPairKeepsLaterShotsAsContinuityOnly() {
        script.setShots(List.of(
                new LinkedHashMap<>(Map.of("shotNumber", 1, "title", "Brand world", "action", "Darkness reveals the chocolate form.")),
                new LinkedHashMap<>(Map.of("shotNumber", 2, "title", "Texture reveal", "action", "The form becomes a chocolate ribbon.")),
                new LinkedHashMap<>(Map.of("shotNumber", 3, "title", "Ingredients", "action", "Ingredients emerge from the ribbon.")),
                new LinkedHashMap<>(Map.of("shotNumber", 4, "title", "Hero", "action", "The approved pack resolves in full."))
        ));
        when(assetRepository.findShotImageAssetsForScript(script.getId(), "tenant-1", "user-1"))
                .thenReturn(List.of());
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())).thenReturn(List.of());
        ArgumentCaptor<Map<String, Object>> providerInput = ArgumentCaptor.forClass(Map.class);
        when(creatorAiService.generateMetered(eq("CLIENT_REVIEW_RAG_CHAT"), providerInput.capture(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        Map.of(
                                "assistantMessage", "I prepared a continuous Shot 1 to Shot 2 opening.",
                                "changeSummary", "Rework the opening pair while preserving the next two shots.",
                                "affectedShotNumbers", List.of(1, 2, 3, 4),
                                "shotRevisions", List.of(
                                        Map.of("shotNumber", 1, "imageRevisionPrompt", "Rework Shot 1."),
                                        Map.of("shotNumber", 2, "imageRevisionPrompt", "Rework Shot 2."),
                                        Map.of("shotNumber", 3, "imageRevisionPrompt", "Do not regenerate Shot 3."),
                                        Map.of("shotNumber", 4, "imageRevisionPrompt", "Do not regenerate Shot 4.")
                                ),
                                "requiresFrameRegeneration", true
                        ),
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));

        Map<String, Object> response = service.chatReview(
                script.getId(),
                new StoryboardClientReviewChatRequest(
                        "review-opening-pair",
                        "Review Shots 1 and Shot 2 together. Continue naturally into Shot 3 ingredients and Shot 4 hero, but keep those later shots unchanged.",
                        "PLANNING",
                        null,
                        "English",
                        Map.of()
                ),
                "tenant-1",
                "user-1"
        );

        assertThat(providerInput.getValue())
                .containsEntry("requestedShotNumbers", List.of(1, 2))
                .containsEntry("continuityOnlyShotNumbers", List.of(3, 4));
        Map<String, Object> proposal = (Map<String, Object>) response.get("proposal");
        assertThat(proposal)
                .containsEntry("affectedShotNumbers", List.of(1, 2))
                .containsEntry("continuityAnchorShotNumbers", List.of(3, 4));
        assertThat((List<Map<String, Object>>) proposal.get("shotRevisions"))
                .extracting(item -> item.get("shotNumber"))
                .containsExactly(1, 2);
    }

    @Test
    void explicitThreeShotSequenceKeepsFourthShotAsContinuityOnly() {
        script.setShots(List.of(
                new LinkedHashMap<>(Map.of("shotNumber", 1, "title", "Brand world", "action", "Darkness reveals the chocolate form.")),
                new LinkedHashMap<>(Map.of("shotNumber", 2, "title", "Texture reveal", "action", "The form becomes a chocolate ribbon.")),
                new LinkedHashMap<>(Map.of("shotNumber", 3, "title", "Ingredients", "action", "Ingredients emerge from the ribbon.")),
                new LinkedHashMap<>(Map.of("shotNumber", 4, "title", "Hero", "action", "The approved pack resolves in full."))
        ));
        when(assetRepository.findShotImageAssetsForScript(script.getId(), "tenant-1", "user-1"))
                .thenReturn(List.of());
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())).thenReturn(List.of());
        ArgumentCaptor<Map<String, Object>> providerInput = ArgumentCaptor.forClass(Map.class);
        when(creatorAiService.generateMetered(eq("CLIENT_REVIEW_RAG_CHAT"), providerInput.capture(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        Map.of(
                                "assistantMessage", "I prepared one continuous three-shot opening.",
                                "changeSummary", "Regenerate Shots 1 through 3 while preserving Shot 4.",
                                "affectedShotNumbers", List.of(1, 2, 3, 4),
                                "shotRevisions", List.of(
                                        Map.of("shotNumber", 1, "imageRevisionPrompt", "Rework Shot 1."),
                                        Map.of("shotNumber", 2, "imageRevisionPrompt", "Rework Shot 2."),
                                        Map.of("shotNumber", 3, "imageRevisionPrompt", "Rework Shot 3."),
                                        Map.of("shotNumber", 4, "imageRevisionPrompt", "Do not regenerate Shot 4.")
                                ),
                                "requiresFrameRegeneration", true
                        ),
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));

        Map<String, Object> response = service.chatReview(
                script.getId(),
                new StoryboardClientReviewChatRequest(
                        "review-opening-sequence",
                        "Generate Shots 1, 2 and 3 as one continuous sequence. Maintain continuity with Shot 4, but keep Shot 4 unchanged.",
                        "PLANNING",
                        null,
                        "English",
                        Map.of()
                ),
                "tenant-1",
                "user-1"
        );

        assertThat(providerInput.getValue())
                .containsEntry("requestedShotNumbers", List.of(1, 2, 3))
                .containsEntry("continuityOnlyShotNumbers", List.of(4));
        Map<String, Object> proposal = (Map<String, Object>) response.get("proposal");
        assertThat(proposal)
                .containsEntry("affectedShotNumbers", List.of(1, 2, 3))
                .containsEntry("continuityAnchorShotNumbers", List.of(4));
        assertThat((List<Map<String, Object>>) proposal.get("shotRevisions"))
                .extracting(item -> item.get("shotNumber"))
                .containsExactly(1, 2, 3);
    }

    @Test
    void applyUsesTheExplicitlySelectedProposalInsteadOfTheLatestPendingPlan() {
        script.setShots(List.of(
                new LinkedHashMap<>(Map.of("shotNumber", 1, "title", "Opening", "action", "Current opening.")),
                new LinkedHashMap<>(Map.of("shotNumber", 2, "title", "Hero", "action", "Current hero."))
        ));
        when(scriptShotRepository.findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(script.getId()))
                .thenReturn(List.of());
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())).thenReturn(List.of());
        ArgumentCaptor<Map<String, Object>> providerInput = ArgumentCaptor.forClass(Map.class);
        when(creatorAiService.generateMetered(eq("CLIENT_FEEDBACK_PROPAGATE"), providerInput.capture(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        Map.of("shots", script.getShots()),
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));

        List<Map<String, Object>> reviewChat = List.of(
                Map.of(
                        "id", "proposal-one",
                        "role", "user",
                        "targetType", "STORYBOARD_AND_PRODUCT",
                        "shotNumber", 1,
                        "affectedShotNumbers", List.of(1),
                        "status", "AWAITING_CONFIRMATION",
                        "text", "Use the first proposed opening."
                ),
                Map.of(
                        "id", "proposal-one-analysis",
                        "role", "assistant",
                        "confirmationForMessageId", "proposal-one",
                        "status", "COMPLETED",
                        "text", "First proposal ready.",
                        "proposal", Map.of(
                                "changeSummary", "First selected proposal",
                                "affectedShotNumbers", List.of(1),
                                "requiresFrameRegeneration", true,
                                "shotRevisions", List.of(Map.of(
                                        "shotNumber", 1,
                                        "imageRevisionPrompt", "Create the selected opening."
                                ))
                        )
                ),
                Map.of(
                        "id", "proposal-two",
                        "role", "user",
                        "targetType", "STORYBOARD_AND_PRODUCT",
                        "shotNumber", 2,
                        "affectedShotNumbers", List.of(2),
                        "status", "AWAITING_CONFIRMATION",
                        "text", "Use the second proposed hero."
                ),
                Map.of(
                        "id", "proposal-two-analysis",
                        "role", "assistant",
                        "confirmationForMessageId", "proposal-two",
                        "status", "COMPLETED",
                        "text", "Second proposal ready.",
                        "proposal", Map.of(
                                "changeSummary", "Latest but unselected proposal",
                                "affectedShotNumbers", List.of(2),
                                "requiresFrameRegeneration", true,
                                "shotRevisions", List.of(Map.of(
                                        "shotNumber", 2,
                                        "imageRevisionPrompt", "Create the unselected hero."
                                ))
                        )
                )
        );

        StoryboardClientReviewResponse response = service.applyReview(
                script.getId(),
                new StoryboardClientReviewRequest(
                        null, null, null, "Hinglish", "CHANGES_REQUESTED",
                        List.of(), List.of(), List.of(), List.of(), reviewChat,
                        Map.of(), List.of(), Map.of(), "proposal-one"
                ),
                "tenant-1",
                "user-1"
        );

        assertThat(providerInput.getValue()).containsEntry("requestedShotNumbers", List.of(1));
        assertThat((Map<String, Object>) providerInput.getValue().get("acceptedReviewProposal"))
                .containsEntry("changeSummary", "First selected proposal")
                .containsEntry("affectedShotNumbers", List.of(1));
        assertThat(response.reviewChat().stream()
                .filter(message -> "proposal-one".equals(message.get("id")))
                .findFirst()
                .orElseThrow()).containsEntry("status", "COMPLETED");
        assertThat(response.reviewChat().stream()
                .filter(message -> "proposal-two".equals(message.get("id")))
                .findFirst()
                .orElseThrow()).containsEntry("status", "AWAITING_CONFIRMATION");
    }

    @Test
    void applyStoresOnlyShotScopedReviewContextInEachPlan() {
        CreatorScriptShotPlan plan = CreatorScriptShotPlan.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-1")
                .userId("user-1")
                .scriptId(script.getId())
                .shotNumber(1)
                .styleKey("indian_creator_pencil")
                .storyboardTag(new LinkedHashMap<>())
                .lightingBuildSheetTag(new LinkedHashMap<>())
                .cameraPlanSheetTag(new LinkedHashMap<>())
                .promptRunIds(new LinkedHashMap<>())
                .inputPayload(new LinkedHashMap<>(Map.of(
                        "clientReview", Map.of("reviewChat", List.of(Map.of("text", "large chat"))),
                        "videoDirectorBlueprint", Map.of("masterVideoPrompt", "large blueprint"),
                        "masterVideoPrompt", "large master prompt",
                        "perSecondVideoPrompt", "large timeline prompt"
                )))
                .status("GENERATED")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        when(scriptShotRepository.findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(script.getId()))
                .thenReturn(List.of());
        when(shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId()))
                .thenReturn(List.of(plan));
        when(creatorAiService.generateMetered(eq("CLIENT_FEEDBACK_PROPAGATE"), any(), any()))
                .thenReturn(new CreatorAiService.MeteredAiResponse(
                        Map.of("shots", script.getShots()),
                        Map.of(),
                        Map.of(),
                        UUID.randomUUID(),
                        java.math.BigDecimal.ZERO
                ));

        service.applyReview(
                script.getId(),
                new StoryboardClientReviewRequest(
                        "Update the frame pair.", "", "", "Hinglish", "CHANGES_REQUESTED",
                        List.of(), List.of(), List.of(), Map.of(), List.of()
                ),
                "tenant-1",
                "user-1"
        );

        assertThat(plan.getInputPayload())
                .doesNotContainKeys("videoDirectorBlueprint", "masterVideoPrompt", "perSecondVideoPrompt");
        assertThat((Map<String, Object>) plan.getInputPayload().get("clientReview"))
                .containsEntry("chatHistoryStoredCentrally", true)
                .doesNotContainKeys("reviewChat", "conversationMemory", "videoDirectorPlan");
    }

    @Test
    void animatedPreviewIncludesFramesDialogueAndSavedDirection() {
        service.saveReview(
                script.getId(),
                new StoryboardClientReviewRequest("Hold the opening reaction.", "", "", "English", "READY_FOR_CLIENT"),
                "tenant-1",
                "user-1"
        );
        CreatorAsset asset = CreatorAsset.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-1")
                .userId("user-1")
                .assetType("STORYBOARD_IMAGE")
                .bucket("creator-assets")
                .objectKey("storyboards/frame-1.jpg")
                .metadata(new LinkedHashMap<>(Map.of("shotNumber", 1, "imageKind", "storyboard")))
                .createdAt(OffsetDateTime.now())
                .build();
        when(assetRepository.findShotImageAssetsForScript(script.getId(), "tenant-1", "user-1"))
                .thenReturn(List.of(asset));
        when(assetStorageService.signedUrl(eq("creator-assets"), eq("storyboards/frame-1.jpg"), any()))
                .thenReturn("https://assets.example/frame-1.jpg");

        String html = service.buildAnimatedPreview(script.getId(), "tenant-1", "user-1");

        assertThat(html)
                .contains("Animated Storyboard")
                .contains("Hold the opening reaction.")
                .contains("This changed everything.")
                .contains("https://assets.example/frame-1.jpg")
                .contains("English dialogue");
    }

    private CreatorAsset visualReferenceAsset(UUID assetId, String objectKey) {
        return CreatorAsset.builder()
                .id(assetId)
                .tenantId("tenant-1")
                .userId("user-1")
                .assetType("STORYBOARD_VISUAL_REFERENCE_IMAGE")
                .bucket("creator-assets")
                .objectKey(objectKey)
                .contentType("image/jpeg")
                .sizeBytes(3L)
                .metadata(new LinkedHashMap<>(Map.of(
                        "scriptId", script.getId().toString(),
                        "originalFilename", objectKey
                )))
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
