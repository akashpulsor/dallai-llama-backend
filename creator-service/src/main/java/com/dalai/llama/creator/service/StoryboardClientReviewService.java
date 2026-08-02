package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShot;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.dto.request.StoryboardClientReviewChatRequest;
import com.dalai.llama.creator.dto.request.StoryboardClientReviewRequest;
import com.dalai.llama.creator.dto.response.StoryboardClientReviewResponse;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class StoryboardClientReviewService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardClientReviewService.class);

    private static final String CLIENT_REVIEW_KEY = "clientReview";
    private static final String CLIENT_REVIEW_SNAPSHOTS_KEY = "clientReviewPlanningSnapshots";
    private static final int MAX_CLIENT_REVIEW_SNAPSHOTS = 5;
    private static final List<String> REVERSIBLE_PLANNING_PAYLOAD_KEYS = List.of(
            "storyline",
            "logline",
            "dialogueLanguage",
            "typographySystem",
            "overlayPlan",
            "videoDirectorPlan",
            "creativeDirection",
            "planningPropagation",
            "contentRules",
            "visualInspirationReferenceImages",
            "visualInspirationReferenceImageUrls",
            "creativeLearning",
            "approvedCreativeLearnings",
            CLIENT_REVIEW_KEY
    );
    private static final Duration PREVIEW_URL_TTL = Duration.ofDays(7);
    private static final long MAX_FONT_REFERENCE_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_FONT_REFERENCE_IMAGES = 12;
    private static final long MAX_VISUAL_REFERENCE_BYTES = 15L * 1024L * 1024L;
    private static final int MAX_VISUAL_REFERENCE_IMAGES = 8;
    private static final int MAX_VISUAL_REFERENCE_HISTORY_IMAGES = 40;
    private static final int MAX_CLIENT_REVIEW_MESSAGE_LENGTH = 24_000;
    private static final int MAX_CLIENT_REVIEW_STORED_MESSAGE_LENGTH = 12_000;
    private static final int MAX_STORED_REVIEW_CHAT_MESSAGES = 24;
    private static final int DETAILED_REVIEW_CHAT_TAIL_MESSAGES = 4;
    private static final int MAX_PROVIDER_REVIEW_HISTORY_MESSAGES = 8;
    private static final int MAX_STORED_REVIEW_MEMORY_ITEMS = 48;
    private static final int MAX_REVIEW_MEMORY_TEXT_LENGTH = 800;
    private static final List<String> DIALOGUE_LOCALIZATION_KEYS = List.of(
            "dialogue",
            "primaryDialogue",
            "voiceOver",
            "voiceover",
            "dialogueScript",
            "exactDialogue",
            "spokenDialogue",
            "spokenText",
            "caption",
            "captionText",
            "subtitle",
            "subtitles"
    );
    private static final List<String> MANAGED_SHOT_PLAN_INPUT_KEYS = List.of(
            "clientReview",
            "dialogueLanguage",
            "emojisAllowed",
            "referenceUrls",
            "visualReferenceImages",
            "visualReferenceImageUrls",
            "visualReferenceUsageMode",
            "typographySystem",
            "overlayPlan",
            "videoDirectorPlan",
            "videoDirectorBlueprint",
            "masterVideoPrompt",
            "perSecondVideoPrompt",
            "regenerationRequired",
            "storyboardRegenerationRequired",
            "productFrameRegenerationRequired"
    );
    private static final Pattern SHOT_REFERENCE_PATTERN = Pattern.compile(
            "(?i)\\bshots?\\s*#?\\s*(\\d+)"
    );
    private static final Pattern SHOT_LIST_PATTERN = Pattern.compile(
            "(?i)\\bshots?\\s+((?:#?\\d+\\s*(?:(?:,|and|&|to|-)\\s*)?)+)"
    );
    private static final Pattern PRIMARY_SHOT_SCOPE_PATTERN = Pattern.compile(
            "(?i)\\b(?:review|revise|rework|redesign|edit|update|change|fix|generate|regenerate|improve|see|make)\\s+"
                    + "(?:only\\s+)?shots?\\s*#?\\s*"
    );
    private static final Pattern PRIMARY_SHOT_SCOPE_BOUNDARY_PATTERN = Pattern.compile(
            "(?i)[.;:\\n]|\\b(?:maintain|preserve|keep|continuity|anchor|reference|read\\s*-?\\s*only|without|while|but)\\b"
    );
    private static final Pattern SHOT_RANGE_PATTERN = Pattern.compile(
            "(?i)#?\\s*(\\d+)\\s*(?:-|to)\\s*(?:shots?\\s*)?#?\\s*(\\d+)"
    );
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final String ASSET_TYPE_FONT_REFERENCE_IMAGE = "STORYBOARD_FONT_REFERENCE_IMAGE";
    private static final String ASSET_TYPE_VISUAL_REFERENCE_IMAGE = "STORYBOARD_VISUAL_REFERENCE_IMAGE";
    private static final List<String> RENDERABLE_FONT_FAMILIES = List.of(
            "Montserrat",
            "Inter",
            "Poppins",
            "Playfair Display",
            "Bebas Neue",
            "Oswald",
            "Lora",
            "Raleway",
            "Roboto Slab",
            "DM Sans"
    );
    private static final String DEFAULT_STORYBOARD_FEEDBACK = "Replace the repetitive chocolate-pouring beat with a distinct product moment while preserving story continuity. Add specific action, blocking, camera, and transition detail.";
    private static final String DEFAULT_PRODUCTION_FEEDBACK = "Use client visual references as inspiration only for mood, composition, lighting, texture, and pacing. Preserve the core ad idea. Never copy a reference name, logo, packaging, claims, trademark, or exact artwork; all product details must come only from this project's approved product data. Add production-ready lighting, styling, texture, and continuity detail.";
    private static final String DEFAULT_DIALOGUE_FEEDBACK = "Use the selected dialogue language consistently. Do not use emojis in dialogue, voice-over, captions, or on-screen overlays. Keep delivery natural and provide performance detail.";
    private static final List<String> FONT_REFERENCE_URLS = List.of(
            "https://fonts.google.com/specimen/Montserrat",
            "https://fonts.google.com/specimen/Inter"
    );

    private final CreatorScriptRepository scriptRepository;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final CreatorScriptShotRepository scriptShotRepository;
    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final CreatorAiService creatorAiService;
    private final CreatorCreativeLearningService creativeLearningService;

    public StoryboardClientReviewService(
            CreatorScriptRepository scriptRepository,
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService,
            CreatorScriptShotRepository scriptShotRepository,
            CreatorScriptShotPlanRepository shotPlanRepository,
            CreatorAiService creatorAiService,
            CreatorCreativeLearningService creativeLearningService
    ) {
        this.scriptRepository = scriptRepository;
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
        this.scriptShotRepository = scriptShotRepository;
        this.shotPlanRepository = shotPlanRepository;
        this.creatorAiService = creatorAiService;
        this.creativeLearningService = creativeLearningService;
    }

    @Transactional(readOnly = true)
    public StoryboardClientReviewResponse getReview(UUID scriptId, String tenantId, String userId) {
        return toResponse(loadScript(scriptId, tenantId, userId));
    }

    @Transactional
    public Map<String, Object> chatReview(
            UUID scriptId,
            StoryboardClientReviewChatRequest request,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        String message = trimToLength(
                request == null ? null : request.message(),
                MAX_CLIENT_REVIEW_MESSAGE_LENGTH,
                ""
        );
        if (message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A client review message is required.");
        }
        String targetType = normalizeReviewTarget(request == null ? null : request.targetType());
        int shotNumber = request == null || request.shotNumber() == null ? 0 : request.shotNumber();

        Map<String, Object> payload = new LinkedHashMap<>(
                script.getScriptPayload() == null ? Map.of() : script.getScriptPayload()
        );
        Map<String, Object> clientReview = new LinkedHashMap<>(mapValue(payload.get(CLIENT_REVIEW_KEY)));
        mergeIncomingReviewContext(
                clientReview,
                request == null ? Map.of() : request.currentReview(),
                script
        );
        String dialogueLanguage = normalizeDialogueLanguage(
                request == null ? null : request.dialogueLanguage(),
                firstNonBlank(clientReview.get("dialogueLanguage"), script.getDialogueLanguage())
        );
        String appliedDialogueLanguage = appliedDialogueLanguage(script);
        boolean dialogueLanguageChangePending = !sameDialogueLanguage(
                appliedDialogueLanguage,
                dialogueLanguage
        );
        clientReview.put("dialogueLanguage", dialogueLanguage);

        List<Map<String, Object>> shots = copyMapList(scriptShots(script));
        List<Integer> requestedShotNumbers = requestedShotNumbers(message, shotNumber, shots);
        List<Integer> continuityOnlyShotNumbers = shots.stream()
                .map(shot -> intValue(shot.get("shotNumber"), 0))
                .filter(value -> value > 0 && !requestedShotNumbers.contains(value))
                .toList();
        int selectedIndex = -1;
        for (int index = 0; index < shots.size(); index++) {
            if (intValue(shots.get(index).get("shotNumber"), index + 1) == shotNumber) {
                selectedIndex = index;
                break;
            }
        }
        if (shotNumber > 0 && selectedIndex < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The selected storyboard shot was not found.");
        }

        Map<String, Object> selectedShot = selectedIndex >= 0
                ? new LinkedHashMap<>(shots.get(selectedIndex))
                : Map.of();
        Map<String, Object> previousShot = selectedIndex > 0
                ? new LinkedHashMap<>(shots.get(selectedIndex - 1))
                : Map.of();
        Map<String, Object> nextShot = selectedIndex >= 0 && selectedIndex + 1 < shots.size()
                ? new LinkedHashMap<>(shots.get(selectedIndex + 1))
                : Map.of();
        List<Map<String, Object>> allProductionPlans = reviewPlanContexts(script);
        Map<String, Object> selectedPlan = allProductionPlans.stream()
                .filter(plan -> intValue(plan.get("shotNumber"), 0) == shotNumber)
                .findFirst()
                .orElse(Map.of());
        Map<Integer, PreviewAssets> previewAssetsByShot = loadPreviewAssets(script);
        List<Map<String, Object>> allShotImages = new ArrayList<>();
        Map<Integer, Map<String, Object>> imagesByShotNumber = new LinkedHashMap<>();
        for (int index = 0; index < shots.size(); index++) {
            Map<String, Object> contextShot = shots.get(index);
            int contextShotNumber = intValue(contextShot.get("shotNumber"), index + 1);
            Map<String, Object> shotImages = reviewImagesForShot(
                    previewAssetsByShot,
                    contextShotNumber,
                    contextShot
            );
            Map<String, Object> numberedImages = new LinkedHashMap<>(shotImages);
            numberedImages.put("shotNumber", contextShotNumber);
            allShotImages.add(numberedImages);
            imagesByShotNumber.put(contextShotNumber, numberedImages);
        }
        Map<String, Object> currentImages = imagesByShotNumber.getOrDefault(shotNumber, Map.of());
        List<Integer> imageContextShotNumbers = requestedShotNumbers.isEmpty()
                ? shots.stream()
                        .map(shot -> intValue(shot.get("shotNumber"), 0))
                        .filter(value -> value > 0)
                        .toList()
                : requestedShotNumbers;
        List<String> selectedImageUrls = new ArrayList<>();
        List<Map<String, Object>> selectedImageAssets = new ArrayList<>();
        for (int contextShotNumber : imageContextShotNumbers) {
            if (selectedImageUrls.size() >= 8 && selectedImageAssets.size() >= 8) break;
            Map<String, Object> contextShot = shots.stream()
                    .filter(item -> intValue(item.get("shotNumber"), 0) == contextShotNumber)
                    .findFirst()
                    .orElse(Map.of());
            reviewImageUrls(
                    "STORYBOARD_AND_PRODUCT",
                    imagesByShotNumber.getOrDefault(contextShotNumber, Map.of()),
                    contextShot
            ).stream()
                    .filter(url -> !selectedImageUrls.contains(url))
                    .limit(Math.max(0, 8 - selectedImageUrls.size()))
                    .forEach(selectedImageUrls::add);
            reviewImageAssets(
                    "STORYBOARD_AND_PRODUCT",
                    previewAssetsByShot,
                    contextShotNumber
            ).stream()
                    .filter(asset -> selectedImageAssets.stream().noneMatch(existing ->
                            stringValue(existing.get("assetId")).equals(stringValue(asset.get("assetId")))))
                    .limit(Math.max(0, 8 - selectedImageAssets.size()))
                    .forEach(selectedImageAssets::add);
        }
        String visualReferenceUsageMode = normalizeVisualReferenceUsageMode(
                request == null ? null : request.visualReferenceUsageMode()
        );
        Set<String> requestedVisualReferenceAssetIds = request == null || request.visualReferenceAssetIds() == null
                ? Set.of()
                : request.visualReferenceAssetIds().stream()
                        .map(this::stringValue)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .limit(MAX_VISUAL_REFERENCE_IMAGES)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, Object>> visualReferenceImages = normalizeVisualReferenceImages(
                clientReview.get("visualReferenceImages"),
                script
        ).stream()
                .filter(asset -> requestedVisualReferenceAssetIds.contains(stringValue(asset.get("assetId"))))
                .<Map<String, Object>>map(asset -> {
                    Map<String, Object> selected = new LinkedHashMap<>(asset);
                    selected.put("usageMode", visualReferenceUsageMode);
                    selected.put("visualReferenceUsageMode", visualReferenceUsageMode);
                    selected.put("referenceRole", "EXACT_SOURCE".equals(visualReferenceUsageMode) ? "exact_visual_source" : "visual_inspiration_only");
                    selected.put("assetRole", selected.get("referenceRole"));
                    return selected;
                })
                .toList();
        for (Map<String, Object> visualReference : visualReferenceImages) {
            String url = firstNonBlank(
                    visualReference.get("signedUrl"),
                    visualReference.get("publicUrl"),
                    visualReference.get("assetUrl")
            );
            if (!url.isBlank() && !selectedImageUrls.contains(url)) selectedImageUrls.add(url);
            selectedImageAssets.add(visualReference);
        }

        String messageId = trimToLength(
                request == null ? null : request.messageId(),
                120,
                "review-" + UUID.randomUUID()
        );
        List<Map<String, Object>> reviewChat = new ArrayList<>(normalizeReviewChat(clientReview.get("reviewChat")));
        Map<String, Object> userMessage = reviewChat.stream()
                .filter(item -> messageId.equals(stringValue(item.get("id"))))
                .findFirst()
                .<Map<String, Object>>map(item -> new LinkedHashMap<>(item))
                .orElseGet(() -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", messageId);
                    item.put("role", "user");
                    item.put("text", stripEmoji(message));
                    item.put("targetType", targetType);
                    item.put("shotNumber", shotNumber > 0 ? shotNumber : "");
                    item.put("status", "AWAITING_ANALYSIS");
                    item.put("createdAt", OffsetDateTime.now().toString());
                    reviewChat.add(item);
                    return item;
                });
        userMessage.put("visualReferenceAssetIds", requestedVisualReferenceAssetIds.stream().toList());
        userMessage.put("visualReferenceUsageMode", visualReferenceUsageMode);
        userMessage.put("affectedShotNumbers", requestedShotNumbers);
        userMessage.put("status", "AWAITING_CONFIRMATION");
        for (int index = 0; index < reviewChat.size(); index++) {
            if (messageId.equals(stringValue(reviewChat.get(index).get("id")))) {
                reviewChat.set(index, userMessage);
                break;
            }
        }

        Map<String, Object> ragContext = new LinkedHashMap<>();
        ragContext.put("project", reviewProjectContext(script, payload, dialogueLanguage));
        ragContext.put("currentStoryline", firstNonBlank(payload.get("storyline"), payload.get("logline")));
        ragContext.put("currentScreenplay", defaultString(script.getScriptText(), ""));
        ragContext.put("selectedShot", compactShotForAi(selectedShot));
        ragContext.put("previousShot", compactShotForAi(previousShot));
        ragContext.put("nextShot", compactShotForAi(nextShot));
        ragContext.put("selectedProductionPlan", compactProductionPlanForAi(selectedPlan));
        ragContext.put("currentImages", currentImages);
        ragContext.put("allShots", shots.stream().map(this::compactShotForAi).toList());
        ragContext.put("allProductionPlans", allProductionPlans.stream()
                .map(this::compactProductionPlanForAi)
                .toList());
        ragContext.put("allShotImages", allShotImages);
        ragContext.put("requestedShotNumbers", requestedShotNumbers);
        ragContext.put("continuityOnlyShotNumbers", continuityOnlyShotNumbers);
        ragContext.put("typographySystem", typographySystem(mapValue(clientReview.get("typographySystem"))));
        ragContext.put("videoDirectorPlan", compactVideoDirectorPlanForAi(videoDirectorPlan(
                firstNonNull(clientReview.get("videoDirectorPlan"), payload.get("videoDirectorPlan")),
                script,
                scriptShots(script),
                typographySystem(mapValue(clientReview.get("typographySystem"))),
                mapList(clientReview.get("overlayPlan"))
        ), requestedShotNumbers, continuityOnlyShotNumbers));
        ragContext.put("selectedOverlayPlan", overlayForShot(clientReview, shotNumber));
        ragContext.put("contentRules", mapValue(payload.get("contentRules")));
        ragContext.put("dialogueLanguageChange", Map.of(
                "pending", dialogueLanguageChangePending,
                "appliedLanguage", appliedDialogueLanguage,
                "selectedLanguage", dialogueLanguage,
                "confirmationRequired", dialogueLanguageChangePending
        ));
        ragContext.put("referenceUrls", normalizeReferenceUrls(stringList(clientReview.get("referenceUrls"))));
        ragContext.put("visualInspirationReferences", visualReferenceImages);
        ragContext.put("visualReferenceUsageMode", visualReferenceUsageMode);
        ragContext.put("conversationMemory", conversationMemoryForAi(clientReview, reviewChat));
        ragContext.put("reviewHistory", compactReviewHistoryForAi(reviewChat));
        ragContext.put("retrievedKeys", List.of(
                "project_and_script",
                "current_storyline_and_screenplay",
                "all_storyboard_shots",
                "all_product_storyboard_lighting_and_dp_frames",
                "all_storyboard_lighting_and_dp_planning_sheets",
                "selected_shot",
                "video_director_plan",
                "adjacent_shots",
                "selected_production_plan",
                "current_storyboard_and_product_images",
                "typography_and_overlay_plan",
                "client_review_history",
                "client_review_conversation_memory"
        ));

        Map<String, Object> providerInput = new LinkedHashMap<>();
        providerInput.put("task", """
                You are a retrieval-grounded, multimodal creative review assistant with complete project context.
                The user may speak naturally about one shot, several shots, transitions between shots, the
                complete ad, or may not select a frame at all. Resolve all shot references from allShots,
                allProductionPlans, allShotImages, the screenplay, and the director blueprint. Never require
                a dropdown or a preselected shot when the message itself identifies the scope.
                When the user supplies a story or narrative with explicit shot references, treat it as the
                authoritative proposed story progression for those shots. Map every referenced story beat to
                one shotRevision, preserve the order and causal transition between them, and generate distinct
                storyboard and product-frame instructions for each affected shot rather than repeating one
                generic prompt. Return storyInterpretation with premise, orderedBeats, continuityArc, openingHook,
                payoff, and shotMappings so the client can verify how the story was understood before Apply.
                requestedShotNumbers is the authoritative edit and regeneration scope when it is not empty.
                Never add a shot outside requestedShotNumbers to affectedShotNumbers or shotRevisions.
                continuityOnlyShotNumbers and every other project shot are read-only continuity anchors:
                inspect their incoming and outgoing states, but do not propose regenerating or rewriting them.
                First identify what is currently present in every affected storyboard, product, lighting,
                and DP frame and its planning sheet. Then interpret the requested change without breaking
                continuity with any preceding or following shot,
                product accuracy, dialogue language, typography, overlay timing, or the production plan.
                Do not claim to see visual details when no image was attached.
                Respect visualReferenceUsageMode. EXACT_SOURCE means the selected client image is the
                visual source of truth and its visible product, logo, packaging, copy, and composition
                must be preserved. INSPIRATION_ONLY means borrow only mood, composition, lighting,
                texture, palette, and pacing while keeping approved project product data unchanged.
                This chat is analysis only. Do not claim the change is already applied.
                conversationMemory contains compact decisions and requests from older turns. Use it together
                with reviewHistory so follow-up messages retain their earlier subjects, constraints, approvals,
                and rejected directions. Newer explicit instructions override older conflicting memory.
                If dialogueLanguageChange.pending is true, explicitly acknowledge the appliedLanguage and
                selectedLanguage. Ask whether the client wants Apply to translate the screenplay dialogue,
                voice-over, captions, subtitles, and overlays to selectedLanguage. If the user's current message
                is an affirmative answer to that question, confirm the choice and tell them Apply to all planning
                will perform and validate the translation; do not ask the same question again.
                Return one JSON object only with:
                assistantMessage, changeSummary, imageRevisionPrompt, proposedShot,
                proposedOverlayPlan, affectedShotNumbers, shotRevisions,
                requiresFrameRegeneration, affectedPlanningStages.
                affectedShotNumbers must list every shot that needs a changed storyboard or product frame.
                shotRevisions must contain one concise object per affected shot with shotNumber,
                changeSummary, currentFrameDescription, proposedFrameDescription, imageRevisionPrompt,
                proposedShot, proposedOverlayPlan, continuityIn, continuityOut, cameraPlan,
                lensFocusPlan, lightingPlan, directionPlan, transitionPlan, soundPlan,
                storyboardChangeRequired, and productFrameChangeRequired. Current and proposed frame
                descriptions must describe materially different visuals and must be specific to that shot.
                Do not return perSecondFrames here; the application expands each approved shot direction
                into second-by-second execution locally so the analysis response stays reliable and compact.
                Plan every affected shot at professional commercial-film standard: ARRI Alexa 35 or
                Sony Venice 2 class cinema capture, a motivated premium lens choice, measured focus
                marks and focus-puller direction, calibrated camera movement, and 4K delivery
                oversampled from 6K/8K when supported. Lighting must be DP and gaffer ready with a
                motivated key, shaped fill or negative fill, controlled rim and separation, declared
                color temperature and contrast intent, modifiers, flagging, reflection control, and
                continuity marks. Direction must state dramatic purpose, product choreography, reveal
                discipline, and the exact end state that motivates the edit. Never propose phone,
                casual, rookie, generic, or uncontrolled automatic camera or lighting execution.
                assistantMessage must state what is present now, what will change, and ask whether the
                user wants to apply the complete change set. Explain that Apply synchronizes the affected
                storyboard and product frames, screenplay, storyboard/lighting/DP planning sheets, overlays,
                and the complete second-by-second final video prompt.
                Return creativeLearningCandidates as a short array of generalized advertising principles that
                should be reused only after the client confirms Apply. Each item must contain principle,
                appliesWhen, avoid, rationale, tags, and priority. Generalize the lesson: never include product
                names, logos, claims, reference URLs, exact reference composition, or client-specific copy.
                Do not learn from draft or rejected feedback and do not say that learning is saved before Apply.
                Each imageRevisionPrompt must be a production-ready image edit/regeneration instruction.
                """);
        providerInput.put("message", message);
        providerInput.put("targetType", targetType);
        providerInput.put("shotNumber", shotNumber);
        providerInput.put("requestedShotNumbers", requestedShotNumbers);
        providerInput.put("continuityOnlyShotNumbers", continuityOnlyShotNumbers);
        providerInput.put("dialogueLanguage", dialogueLanguage);
        providerInput.put("ragContext", ragContext);
        providerInput.put("visualReferenceUsageMode", visualReferenceUsageMode);
        providerInput.put("referenceImageUrls", selectedImageUrls.stream().limit(8).toList());
        providerInput.put("referenceImageAssets", selectedImageAssets.stream().limit(8).toList());
        providerInput.put("referenceImageManifest", Map.of(
                "currentProjectFrameCount", Math.max(0, selectedImageAssets.size() - visualReferenceImages.size()),
                "clientReferenceCount", visualReferenceImages.size(),
                "clientReferenceUsage", visualReferenceUsageMode
        ));
        providerInput.put("attachReferenceImages", !selectedImageUrls.isEmpty() || !selectedImageAssets.isEmpty());

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                defaultString(tenantId, "unknown"),
                defaultString(userId, "anonymous"),
                script.getProjectId(),
                null,
                null
        );
        CreatorAiService.MeteredAiResponse aiResponse = null;
        Map<String, Object> aiOutput;
        try {
            aiResponse = creatorAiService.generateMetered(
                    "CLIENT_REVIEW_RAG_CHAT",
                    providerInput,
                    usageContext
            );
            aiOutput = mapValue(aiResponse.output());
            if (!hasUsableReviewAnalysis(aiOutput, requestedShotNumbers, ragContext)) {
                log.warn(
                        "Client review AI returned incomplete shot analysis; rebuilding from explicit instructions scriptId={} requestedShotNumbers={} outputKeys={} finishReason={}",
                        scriptId,
                        requestedShotNumbers,
                        aiOutput.keySet(),
                        firstNonBlank(
                                aiOutput.get("finishReason"),
                                mapValue(aiOutput.get("metadata")).get("finishReason"),
                                "unknown"
                        )
                );
                aiOutput = groundedReviewFallback(message, requestedShotNumbers, ragContext, targetType);
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Client review AI analysis failed; using grounded confirmation fallback scriptId={} shotNumber={} targetType={} errorType={} errorMessage={}",
                    scriptId,
                    shotNumber,
                    targetType,
                    ex.getClass().getSimpleName(),
                    ex.getMessage()
            );
            aiOutput = groundedReviewFallback(message, requestedShotNumbers, ragContext, targetType);
        }
        Map<String, Object> proposal = reviewProposal(
                aiOutput,
                message,
                targetType,
                shotNumber,
                selectedShot,
                ragContext,
                Math.min(8, Math.max(selectedImageUrls.size(), selectedImageAssets.size()))
        );
        List<Integer> affectedShotNumbers = shotNumberList(proposal.get("affectedShotNumbers"), shots);
        boolean requiresFrameRegeneration = Boolean.TRUE.equals(proposal.get("requiresFrameRegeneration"))
                && !affectedShotNumbers.isEmpty();
        String resolvedTargetType = requiresFrameRegeneration ? "STORYBOARD_AND_PRODUCT" : "PLANNING";
        userMessage.put("targetType", resolvedTargetType);
        userMessage.put("shotNumber", affectedShotNumbers.size() == 1 ? affectedShotNumbers.get(0) : "");
        userMessage.put("affectedShotNumbers", affectedShotNumbers);
        String assistantText = ensureApplyConfirmationQuestion(trimToLength(
                firstNonBlank(aiOutput.get("assistantMessage"), aiOutput.get("message"), proposal.get("changeSummary")),
                4000,
                "I retrieved the current project and prepared the requested revision."
        ), resolvedTargetType, affectedShotNumbers);
        assistantText = ensureDialogueLanguageConfirmation(
                assistantText,
                message,
                appliedDialogueLanguage,
                dialogueLanguage,
                dialogueLanguageChangePending
        );
        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("id", messageId + "-analysis");
        assistantMessage.put("role", "assistant");
        assistantMessage.put("text", stripEmoji(assistantText));
        assistantMessage.put("targetType", resolvedTargetType);
        assistantMessage.put("shotNumber", affectedShotNumbers.size() == 1 ? affectedShotNumbers.get(0) : "");
        assistantMessage.put("affectedShotNumbers", affectedShotNumbers);
        assistantMessage.put("confirmationForMessageId", messageId);
        assistantMessage.put("visualReferenceUsageMode", visualReferenceUsageMode);
        assistantMessage.put("status", "COMPLETED");
        assistantMessage.put("createdAt", OffsetDateTime.now().toString());
        if (dialogueLanguageChangePending) {
            assistantMessage.put("languageChangePrompt", true);
            assistantMessage.put("sourceDialogueLanguage", appliedDialogueLanguage);
            assistantMessage.put("targetDialogueLanguage", dialogueLanguage);
        }
        assistantMessage.put("proposal", proposal);
        assistantMessage.put("retrievedKeys", ragContext.get("retrievedKeys"));
        assistantMessage.put("attachedImageCount", intValue(aiOutput.get("attachedReferenceImageCount"), Math.min(8, Math.max(selectedImageUrls.size(), selectedImageAssets.size()))));
        reviewChat.add(assistantMessage);

        clientReview.put("conversationMemory", updateConversationMemory(clientReview, reviewChat));
        clientReview.put("reviewChat", normalizeReviewChat(reviewChat));
        clientReview.put("reviewStatus", "CHANGES_REQUESTED");
        clientReview.put("updatedAt", OffsetDateTime.now().toString());
        clientReview.put("updatedBy", defaultString(userId, "anonymous"));
        payload.put(CLIENT_REVIEW_KEY, clientReview);
        compactPlanningSnapshots(payload);
        script.setScriptPayload(payload);
        scriptRepository.save(script);
        if (aiResponse != null) {
            addClientReviewPackageMetadata(
                    aiResponse,
                    intValue(clientReview.get("appliedReviewCount"), 0) + 1
            );
            creatorAiService.publishBillingDebit("CLIENT_REVIEW_RAG_CHAT", aiResponse, usageContext);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ANALYZED");
        response.put("messageId", messageId);
        response.put("userMessage", userMessage);
        response.put("assistantMessage", assistantMessage);
        response.put("proposal", proposal);
        response.put("currentImages", currentImages);
        response.put("retrievedKeys", ragContext.get("retrievedKeys"));
        response.put("attachedImageCount", assistantMessage.get("attachedImageCount"));
        response.put("review", toResponse(script));
        return response;
    }

    @Transactional
    public Map<String, Object> uploadFontReferenceImage(
            UUID scriptId,
            MultipartFile file,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> payload = new LinkedHashMap<>(
                script.getScriptPayload() == null ? Map.of() : script.getScriptPayload()
        );
        Map<String, Object> clientReview = new LinkedHashMap<>(mapValue(payload.get(CLIENT_REVIEW_KEY)));
        List<Map<String, Object>> images = new ArrayList<>(normalizeFontReferenceImages(
                clientReview.get("fontReferenceImages"),
                script
        ));
        if (images.size() >= MAX_FONT_REFERENCE_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A maximum of 12 font reference images is allowed.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an image containing the font style.");
        }
        if (file.getSize() > MAX_FONT_REFERENCE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Font reference images must be 10 MB or smaller.");
        }
        String contentType = defaultString(file.getContentType(), "application/octet-stream").toLowerCase(Locale.ROOT);
        String extension = switch (contentType) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/avif" -> "avif";
            case "image/gif" -> "gif";
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Font reference must be a JPG, PNG, WebP, AVIF, or GIF image."
            );
        };

        String objectKey = "screenplay-videos/%s/font-references/%s.%s".formatted(
                script.getId(),
                UUID.randomUUID(),
                extension
        );
        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(
                    objectKey,
                    file.getBytes(),
                    contentType,
                    PREVIEW_URL_TTL
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded font reference.", ex);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        metadata.put("source", "user_upload");
        metadata.put("referenceRole", "typography_style_reference");
        metadata.put("assetRole", "typography_style_reference");
        metadata.put("originalFilename", trimToLength(file.getOriginalFilename(), 255, "font-reference." + extension));
        metadata.put("storageStatus", "SAVED_TO_MINIO");

        CreatorAsset asset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(ASSET_TYPE_FONT_REFERENCE_IMAGE)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        Map<String, Object> uploaded = fontReferenceSummary(asset);
        images.add(uploaded);
        clientReview.put("fontReferenceImages", images);
        clientReview.putIfAbsent("reviewStatus", "CHANGES_REQUESTED");
        clientReview.put("updatedAt", OffsetDateTime.now().toString());
        clientReview.put("updatedBy", defaultString(userId, "anonymous"));
        payload.put(CLIENT_REVIEW_KEY, clientReview);
        script.setScriptPayload(payload);
        scriptRepository.save(script);
        return uploaded;
    }

    @Transactional
    public Map<String, Object> uploadVisualReferenceImage(
            UUID scriptId,
            MultipartFile file,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> payload = new LinkedHashMap<>(
                script.getScriptPayload() == null ? Map.of() : script.getScriptPayload()
        );
        Map<String, Object> clientReview = new LinkedHashMap<>(mapValue(payload.get(CLIENT_REVIEW_KEY)));
        List<Map<String, Object>> images = new ArrayList<>(normalizeVisualReferenceImages(
                clientReview.get("visualReferenceImages"),
                script
        ));
        if (images.size() >= MAX_VISUAL_REFERENCE_HISTORY_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reference history can contain a maximum of 40 visual images.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a visual inspiration image.");
        }
        if (file.getSize() > MAX_VISUAL_REFERENCE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Visual reference images must be 15 MB or smaller.");
        }
        String contentType = defaultString(file.getContentType(), "application/octet-stream").toLowerCase(Locale.ROOT);
        String extension = switch (contentType) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Visual inspiration must be a JPG, PNG, or WebP image."
            );
        };

        String objectKey = "screenplay-videos/%s/visual-inspiration-references/%s.%s".formatted(
                script.getId(),
                UUID.randomUUID(),
                extension
        );
        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(
                    objectKey,
                    file.getBytes(),
                    contentType,
                    PREVIEW_URL_TTL
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the visual inspiration image.", ex);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scriptId", script.getId().toString());
        metadata.put("projectId", script.getProjectId() == null ? null : script.getProjectId().toString());
        metadata.put("source", "user_upload");
        metadata.put("referenceRole", "visual_inspiration_only");
        metadata.put("assetRole", "visual_inspiration_only");
        metadata.put("usageMode", "INSPIRATION_ONLY");
        metadata.put("originalFilename", trimToLength(file.getOriginalFilename(), 255, "visual-reference." + extension));
        metadata.put("storageStatus", "SAVED_TO_MINIO");

        CreatorAsset asset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType(ASSET_TYPE_VISUAL_REFERENCE_IMAGE)
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(metadata)
                .build());

        Map<String, Object> uploaded = visualReferenceSummary(asset);
        images.add(uploaded);
        clientReview.put("visualReferenceImages", images);
        clientReview.put("referenceUsageMode", "INSPIRATION_ONLY");
        clientReview.putIfAbsent("reviewStatus", "CHANGES_REQUESTED");
        clientReview.put("updatedAt", OffsetDateTime.now().toString());
        clientReview.put("updatedBy", defaultString(userId, "anonymous"));
        payload.put(CLIENT_REVIEW_KEY, clientReview);
        script.setScriptPayload(payload);
        scriptRepository.save(script);
        return uploaded;
    }

    @Transactional
    public StoryboardClientReviewResponse saveReview(
            UUID scriptId,
            StoryboardClientReviewRequest request,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        String safeUserId = defaultString(userId, "anonymous");
        OffsetDateTime now = OffsetDateTime.now();
        String dialogueLanguage = normalizeDialogueLanguage(
                request == null ? null : request.dialogueLanguage(),
                script.getDialogueLanguage()
        );

        Map<String, Object> existingPayload = script.getScriptPayload() == null
                ? Map.of()
                : script.getScriptPayload();
        Map<String, Object> existingReview = mapValue(existingPayload.get(CLIENT_REVIEW_KEY));
        Map<String, Object> clientReview = new LinkedHashMap<>();
        clientReview.put("storyboardFeedback", trimToLength(request == null ? null : request.storyboardFeedback(), 6000, DEFAULT_STORYBOARD_FEEDBACK));
        clientReview.put("productionFramesFeedback", trimToLength(request == null ? null : request.productionFramesFeedback(), 6000, DEFAULT_PRODUCTION_FEEDBACK));
        clientReview.put("dialogueFeedback", trimToLength(request == null ? null : request.dialogueFeedback(), 6000, DEFAULT_DIALOGUE_FEEDBACK));
        clientReview.put("dialogueLanguage", dialogueLanguage);
        clientReview.put("reviewStatus", normalizeStatus(request == null ? null : request.reviewStatus()));
        clientReview.put("frameFeedback", normalizeFrameFeedback(request == null ? null : request.frameFeedback()));
        clientReview.put("referenceUrls", normalizeReferenceUrls(request == null ? null : request.referenceUrls()));
        clientReview.put("visualReferenceImages", normalizeVisualReferenceImages(
                request == null ? null : request.visualReferenceImages(),
                script
        ));
        clientReview.put("referenceUsageMode", "INSPIRATION_ONLY");
        clientReview.put("fontReferenceImages", normalizeFontReferenceImages(
                request == null ? null : request.fontReferenceImages(),
                script
        ));
        List<Map<String, Object>> requestedReviewChat = mapList(request == null ? null : request.reviewChat());
        clientReview.put("conversationMemory", updateConversationMemory(existingReview, requestedReviewChat));
        clientReview.put("reviewChat", normalizeReviewChat(requestedReviewChat));
        Map<String, Object> typography = typographySystem(request == null ? Map.of() : request.typographySystem());
        List<Map<String, Object>> overlayPlan = copyMapList(request == null ? null : request.overlayPlan());
        clientReview.put("typographySystem", typography);
        clientReview.put("overlayPlan", overlayPlan);
        clientReview.put("videoDirectorPlan", videoDirectorPlan(
                request == null ? Map.of() : request.videoDirectorPlan(),
                script,
                scriptShots(script),
                typography,
                overlayPlan
        ));
        clientReview.put("emojisAllowed", false);
        clientReview.put("detailLevel", "production_ready");
        if (existingReview.containsKey("creativeLearning")) {
            clientReview.put("creativeLearning", copyJsonValue(existingReview.get("creativeLearning")));
        }
        clientReview.put("appliedReviewCount", intValue(existingReview.get("appliedReviewCount"), 0));
        clientReview.put("includedReviewRoundLimit", 2);
        clientReview.put("updatedAt", now.toString());
        clientReview.put("updatedBy", safeUserId);

        Map<String, Object> payload = new LinkedHashMap<>(
                script.getScriptPayload() == null ? Map.of() : script.getScriptPayload()
        );
        payload.put(CLIENT_REVIEW_KEY, clientReview);
        script.setScriptPayload(payload);
        scriptRepository.save(script);
        return toResponse(script);
    }

    @Transactional
    public StoryboardClientReviewResponse applyReview(
            UUID scriptId,
            StoryboardClientReviewRequest request,
            String tenantId,
            String userId
    ) {
        saveReview(scriptId, request, tenantId, userId);
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> payload = new LinkedHashMap<>(
                script.getScriptPayload() == null ? Map.of() : script.getScriptPayload()
        );
        Map<String, Object> clientReview = new LinkedHashMap<>(mapValue(payload.get(CLIENT_REVIEW_KEY)));
        String selectedReviewMessageId = trimToLength(
                request == null ? null : request.selectedReviewMessageId(),
                120,
                ""
        );
        if (!selectedReviewMessageId.isBlank()
                && selectedActiveReviewInstructions(clientReview, selectedReviewMessageId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The selected proposed plan is no longer available to apply. Select an active proposal and retry."
            );
        }
        int reviewRound = intValue(clientReview.get("appliedReviewCount"), 0) + 1;
        boolean includedReviewRound = reviewRound <= 2;
        List<Map<String, Object>> currentShots = copyMapList(scriptShots(script));
        Map<String, Object> planningSnapshot = createPlanningSnapshot(script, payload, currentShots);
        String dialogueLanguage = normalizeDialogueLanguage(
                clientReview.get("dialogueLanguage"),
                script.getDialogueLanguage()
        );
        String sourceDialogueLanguage = appliedDialogueLanguage(script);
        Map<String, Object> draftTypography = typographySystem(mapValue(clientReview.get("typographySystem")));
        String overlayPolicy = normalizeOverlayPolicy(draftTypography.get("overlayPolicy"));
        Map<String, Object> draftVideoDirectorPlan = videoDirectorPlan(
                clientReview.get("videoDirectorPlan"),
                script,
                currentShots,
                draftTypography,
                mapList(clientReview.get("overlayPlan"))
        );
        List<Map<String, Object>> visualReferenceImages = normalizeVisualReferenceImages(
                clientReview.get("visualReferenceImages"),
                script
        );
        List<Map<String, Object>> activeVisualReferenceImages = activeVisualReferenceImages(
                clientReview,
                visualReferenceImages,
                selectedReviewMessageId
        );
        List<String> visualReferenceImageUrls = referenceImageUrls(
                activeVisualReferenceImages,
                MAX_VISUAL_REFERENCE_IMAGES
        );
        List<Map<String, Object>> fontReferenceImages = normalizeFontReferenceImages(
                clientReview.get("fontReferenceImages"),
                script
        );
        List<String> fontReferenceImageUrls = fontReferenceImageUrls(fontReferenceImages);
        List<String> multimodalReferenceUrls = new ArrayList<>();
        visualReferenceImageUrls.stream().limit(4).forEach(multimodalReferenceUrls::add);
        fontReferenceImageUrls.stream()
                .filter(url -> !multimodalReferenceUrls.contains(url))
                .limit(Math.max(0, 8 - multimodalReferenceUrls.size()))
                .forEach(multimodalReferenceUrls::add);
        List<Map<String, Object>> multimodalReferenceAssets = new ArrayList<>();
        activeVisualReferenceImages.stream().limit(4).forEach(multimodalReferenceAssets::add);
        fontReferenceImages.stream()
                .filter(item -> multimodalReferenceAssets.stream().noneMatch(existing ->
                        stringValue(existing.get("assetId")).equals(stringValue(item.get("assetId")))))
                .limit(Math.max(0, 8 - multimodalReferenceAssets.size()))
                .forEach(multimodalReferenceAssets::add);
        List<Integer> applyShotNumbers = activeReviewShotNumbers(
                clientReview,
                currentShots,
                selectedReviewMessageId
        );
        List<Integer> applyContinuityShotNumbers = currentShots.stream()
                .map(shot -> intValue(shot.get("shotNumber"), 0))
                .filter(shotNumber -> shotNumber > 0 && !applyShotNumbers.contains(shotNumber))
                .toList();
        Map<String, Object> providerClientReview = compactClientReviewForAi(
                clientReview,
                activeVisualReferenceImages,
                selectedReviewMessageId
        );
        Map<String, Object> acceptedReviewProposal = latestReviewProposalForAi(
                clientReview,
                selectedReviewMessageId
        );

        Map<String, Object> providerInput = new LinkedHashMap<>();
        providerInput.put("task", """
                Apply the client review across the complete creative plan. Return one JSON object only.
                Revise the storyline and screenplay where needed, and return a detailed shots array.
                Dialogue, voice-over, captions, and overlay copy must use the selected dialogueLanguage
                supplied in constraints and contain no emojis.
                When sourceDialogueLanguage differs from dialogueLanguage, translate every existing language-bearing
                field rather than changing only metadata. Preserve meaning, speaker attribution, approved product
                names and claims, timing intent, and tone. Return dialogueLocalization with translationApplied=true,
                sourceLanguage, targetLanguage, and translatedShotNumbers. Every shot containing dialogue,
                voice-over, caption, or subtitle input must return the corresponding localized fields and its
                dialogueLanguage. Every enabled overlay containing copy must return localized copy in overlayPlan.
                Replace the repetitive chocolate-pouring visual with a clearly different product-detail,
                texture, consumption, reaction, or finished-pack beat while preserving continuity.
                Add production-ready action, blocking, camera, lighting, product, transition, and post detail.
                Treat typography as an optional post-production layer governed by overlayPolicy. DISABLED means
                no shot may contain on-screen text. ENABLED means use a restrained, selective overlay plan.
                AUTO means use overlays for advertising when they strengthen comprehension, but keep narrative
                storytelling visual-first unless text is genuinely useful. Never put text on every shot.
                Act as a senior advertising editor and art director. Do not expect the user to choose font weight,
                point size, coordinates, safe-zone placement, transition, or millisecond pacing. Inspect each shot's
                composition, product focal area, movement vector, contrast, and duration, then make those decisions
                with premium restraint. Keep the product, logo, face, and important action unobstructed. Treat enabled
                typography as both an image-composition requirement and an executable video post-production layer.
                For each shot return overlayPlan with enabled, text, copyRole, stylePresetId, stylePresetName,
                fontFamily, fontWeight, fontSizePx, backgroundStyle, textColor, accentColor, position, safeZone,
                entrance, entranceDurationMs, delayMs, holdDurationMs, exit, exitDurationMs, speed, rationale,
                compositionRationale, transitionPrompt, pacingRationale, and finalVideoPromptClause.
                Keep copy concise, readable, and based only on approved project facts; never invent a product
                claim, ingredient, nutrition value, offer, name, or CTA. Use only supplied references and identify
                missing visual references rather than inventing them.
                Only activeVisualReferenceImages were explicitly attached to a currently pending review message.
                Images in reference history that are not active must not influence this apply request. Active
                client visual reference images are inspiration only. Use them for mood, composition, lighting,
                texture, rhythm, and broad art direction, but never copy their product names, logos, package
                text, trademarks, distinctive artwork, or exact layout. Product identity and copy must come
                from this project and may intentionally differ from the inspiration.
                Font reference images are visual typography samples, not installable font files. Inspect their
                letterforms, contrast, width, spacing, personality, and case treatment. Choose the closest
                renderable web/video font and never claim an exact match from an image alone. Return
                matchRationale, approximation, confidence, styleTraits, and matchedFromFontReferences inside
                typographySystem. Use the selected font family in every enabled overlayPlan item.
                Preserve client-authored overlayPlan items whose locked field is true. Apply reviewChat
                instructions to their target shot and target type, then propagate the result across planning.
                acceptedReviewProposal is the exact client-visible revision preview that was confirmed. Preserve
                its story interpretation, affected-shot scope, continuity decisions, and shot-specific intent;
                refine production detail without silently replacing the approved narrative with a different idea.
                Return videoDirectorPlan as a complete production blueprint. For product showcase advertising,
                the first 2-3 seconds must establish desire, mystery, scale, and premium brand world before
                ingredient explanation or a full-pack reveal. Use the progression brand-world hook, artifact-like
                partial reveal, texture/craft, ingredients, then earned full-product hero. Early product visibility
                should normally remain 0-20 percent. Move light as deliberately as the camera; specify slow dolly,
                macro slider, tiny orbit, rack focus, lens, focus behavior, negative fill, amber rim light, haze,
                sound, and motivated transitions where appropriate. Avoid repetitive pouring shots.
                videoDirectorPlan must contain a shot-by-shot generationPrompt and perSecondFrames covering every
                second with frameDescription, cameraAction, lightingAction, focusAction, continuityAnchor,
                productVisibilityPercent, and promptSegment. Preserve exact approved product identity, name, logo,
                packaging, claims, colors, and proportions. References may inspire atmosphere and craft only.
                Every enabled overlay must be included in its shot generationPrompt with exact copy, chosen font
                approximation, hierarchy, safe placement, entrance/exit transition, and timed pacing. The same
                decision must appear in masterVideoPrompt so the video page cannot lose the editor's typography.
                Preserve client-locked director shots and make masterVideoPrompt usable directly by video generation.
                Return creativeLearnings as a concise set of reusable principles distilled from the confirmed
                revision. Each item must contain principle, appliesWhen, avoid, rationale, tags, and priority.
                Generalize away product names, claims, logos, reference images, exact layouts, and campaign copy.
                These learnings are stored only because this Apply confirms them; never treat draft chat as learning.
                All advertising plans must use professional commercial-production standards, never rookie, casual,
                phone-camera, or vague auto-exposure direction. Specify a 4K mastering resolution at minimum,
                a cinema-camera package, professional lenses and focus control, frame rate, shutter, log/RAW or
                10/12-bit capture intent, exposure discipline, and a director/DP rationale. Lighting must be designed
                as a gaffer-ready plan with motivated key, fill or negative fill, rim/separation, modifiers, color
                temperature, contrast ratio, reflection control, and continuity. Direction must state the dramatic
                intention, reveal discipline, blocking or product choreography, and transition purpose for every shot.
                """);
        providerInput.put("requiredOutput", Map.of(
                "storyline", "string",
                "screenplay", "string",
                "creativeDirection", "object",
                "typographySystem", "object",
                "overlayPlan", "array with one item per shot",
                "videoDirectorPlan", "complete shot-by-shot and second-by-second video production blueprint",
                "creativeLearnings", "array of generalized approved future-ad principles",
                "shots", "array with the complete revised shot objects",
                "dialogueLocalization", "object with translationApplied, sourceLanguage, targetLanguage, translatedShotNumbers"
        ));
        providerInput.put("clientReview", providerClientReview);
        providerInput.put("acceptedReviewProposal", acceptedReviewProposal);
        providerInput.put("currentStoryline", firstNonBlank(payload.get("storyline"), payload.get("logline")));
        providerInput.put("currentScreenplay", defaultString(script.getScriptText(), ""));
        providerInput.put("currentShots", currentShots.stream().map(this::compactShotForAi).toList());
        providerInput.put("sourceDialogueLanguage", sourceDialogueLanguage);
        providerInput.put("requestedShotNumbers", applyShotNumbers);
        providerInput.put("continuityOnlyShotNumbers", applyContinuityShotNumbers);
        providerInput.put("referenceUrls", normalizeReferenceUrls(stringList(clientReview.get("referenceUrls"))));
        providerInput.put("visualReferenceImages", activeVisualReferenceImages);
        providerInput.put("activeVisualReferenceImages", activeVisualReferenceImages);
        providerInput.put("visualReferenceHistoryCount", visualReferenceImages.size());
        providerInput.put("fontReferenceImages", fontReferenceImages);
        providerInput.put("referenceImageUrls", multimodalReferenceUrls);
        providerInput.put("referenceImageAssets", multimodalReferenceAssets);
        providerInput.put("referenceImageManifest", Map.of(
                "visualInspirationCount", activeVisualReferenceImages.size(),
                "visualReferenceHistoryCount", visualReferenceImages.size(),
                "visualInspirationUsage", "INSPIRATION_ONLY",
                "typographySampleCount", fontReferenceImages.size()
        ));
        providerInput.put("attachReferenceImages", !multimodalReferenceUrls.isEmpty() || !multimodalReferenceAssets.isEmpty());
        providerInput.put("fontCandidates", RENDERABLE_FONT_FAMILIES);
        providerInput.put("typographyDraft", draftTypography);
        providerInput.put("typographyStyleCatalog", typographyStyleCatalog());
        providerInput.put(
                "videoDirectorPlanDraft",
                compactVideoDirectorPlanForAi(
                        draftVideoDirectorPlan,
                        applyShotNumbers,
                        applyContinuityShotNumbers
                )
        );
        providerInput.put("videoDirectorRules", Map.of(
                "opening", "Desire and mystery first; no ingredients or complete pack in the first 2-3 seconds",
                "arc", List.of("Brand world", "Artifact discovery", "Texture and craft", "Ingredients", "Hero product"),
                "earlyProductVisibilityPercent", "0-20",
                "identityPolicy", "Preserve exact approved project product identity; references are inspiration only",
                "detailLevel", "Every shot and every second must have generation-ready direction",
                "mastering", "4K minimum; 2160x3840 vertical or 3840x2160 horizontal, with 6K/8K cinema capture when supported",
                "cameraStandard", "Professional cinema camera, controlled lenses/focus/exposure/motion; never rookie or casual capture",
                "lightingStandard", "Professional DP and gaffer plan with motivated sources, ratios, modifiers, color temperature, reflection control, and continuity",
                "directionStandard", "Commercial director intent, product choreography, reveal discipline, and motivated transitions"
        ));
        providerInput.put("constraints", Map.of(
                "dialogueLanguage", dialogueLanguage,
                "emojisAllowed", false,
                "referenceSharingRequired", true,
                "detailLevel", "production_ready",
                "visualReferenceUsageMode", "INSPIRATION_ONLY",
                "overlayPolicy", overlayPolicy
        ));
        providerInput.put("billingPolicy", Map.of(
                "packageCode", "AI_SHORT_STARTER_60",
                "reviewRound", reviewRound,
                "includedReviewRoundLimit", 2,
                "includedInPackage", includedReviewRound
        ));

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                defaultString(tenantId, "unknown"),
                defaultString(userId, "anonymous"),
                script.getProjectId(),
                null,
                null
        );
        CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(
                "CLIENT_FEEDBACK_PROPAGATE",
                providerInput,
                usageContext
        );
        addClientReviewPackageMetadata(aiResponse, reviewRound);
        Map<String, Object> aiOutput = mapValue(aiResponse.output());
        creatorAiService.publishBillingDebit("CLIENT_FEEDBACK_PROPAGATE", aiResponse, usageContext);
        if (!sameDialogueLanguage(sourceDialogueLanguage, dialogueLanguage)
                && hasLocalizableDialogueContent(
                        script.getScriptText(),
                        currentShots,
                        mapList(clientReview.get("overlayPlan"))
                )
                && !hasCompleteDialogueLocalization(
                        script.getScriptText(),
                        currentShots,
                        mapList(clientReview.get("overlayPlan")),
                        aiOutput,
                        sourceDialogueLanguage,
                        dialogueLanguage
                )) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "The review provider did not return a complete " + dialogueLanguage
                            + " dialogue translation. The current plan was left unchanged."
            );
        }
        List<String> referenceUrls = normalizeReferenceUrls(stringList(clientReview.get("referenceUrls")));
        Map<String, Object> typographyCandidate = new LinkedHashMap<>(draftTypography);
        typographyCandidate.putAll(mapValue(aiOutput.get("typographySystem")));
        preserveClientTypographyChoice(typographyCandidate, draftTypography);
        Map<String, Object> typography = typographySystem(typographyCandidate);
        typography.put("fontReferenceImageCount", fontReferenceImages.size());
        if (!fontReferenceImages.isEmpty()) {
            typography.putIfAbsent("matchedFromFontReferences", true);
            typography.putIfAbsent("approximation", true);
            typography.putIfAbsent(
                    "matchRationale",
                    "Closest renderable match selected from the uploaded typography samples."
            );
        }
        List<Map<String, Object>> overlayPlan = overlayPlanForShots(
                mapList(aiOutput.get("overlayPlan")),
                mapList(clientReview.get("overlayPlan")),
                currentShots,
                typography
        );
        List<Map<String, Object>> updatedShots = applyAiShotChanges(
                currentShots,
                mapList(aiOutput.get("shots")),
                overlayPlan,
                typography,
                referenceUrls,
                clientReview,
                dialogueLanguage
        );
        Map<String, Object> videoDirectorPlan = videoDirectorPlan(
                firstNonNull(aiOutput.get("videoDirectorPlan"), clientReview.get("videoDirectorPlan")),
                script,
                updatedShots,
                typography,
                overlayPlan
        );
        videoDirectorPlan.put("draftOnly", false);
        updatedShots = attachVideoDirectorPlan(updatedShots, videoDirectorPlan);
        Map<String, Object> propagation = propagationSummary(updatedShots.size(), 0, dialogueLanguage);

        String revisedStoryline = stringValue(aiOutput.get("storyline"));
        if (!revisedStoryline.isBlank()) payload.put("storyline", stripEmoji(revisedStoryline));
        String revisedScreenplay = stringValue(aiOutput.get("screenplay"));
        if (!revisedScreenplay.isBlank()) script.setScriptText(stripEmoji(revisedScreenplay));
        payload.put("shots", updatedShots);
        payload.put("dialogueLanguage", dialogueLanguage);
        payload.put("typographySystem", typography);
        payload.put("overlayPlan", overlayPlan);
        payload.put("videoDirectorPlan", videoDirectorPlan);
        payload.put("visualInspirationReferenceImages", activeVisualReferenceImages);
        payload.put("visualInspirationReferenceImageUrls", visualReferenceImageUrls);
        payload.put("creativeDirection", creativeDirection(aiOutput, clientReview, referenceUrls, dialogueLanguage));
        payload.put("planningPropagation", propagation);
        payload.put("contentRules", Map.of(
                "dialogueLanguage", dialogueLanguage,
                "emojisAllowed", false,
                "referenceSharingRequired", true,
                "detailLevel", "production_ready",
                "visualReferenceUsageMode", "INSPIRATION_ONLY"
        ));
        clientReview.put("dialogueLanguage", dialogueLanguage);
        clientReview.put("referenceUrls", referenceUrls);
        clientReview.put("visualReferenceImages", visualReferenceImages);
        clientReview.put("referenceUsageMode", "INSPIRATION_ONLY");
        clientReview.put("fontReferenceImages", fontReferenceImages);
        clientReview.put("typographySystem", typography);
        clientReview.put("overlayPlan", overlayPlan);
        clientReview.remove("videoDirectorPlan");
        clientReview.put("propagation", propagation);
        clientReview.put("appliedReviewCount", reviewRound);
        clientReview.put("includedReviewRoundLimit", 2);
        clientReview.put("lastAppliedReviewIncludedInPackage", includedReviewRound);
        clientReview.put("updatedAt", OffsetDateTime.now().toString());
        payload.put(CLIENT_REVIEW_KEY, clientReview);

        compactPlanningSnapshots(payload);
        script.setDialogueLanguage(dialogueLanguage);
        script.setShots(updatedShots);
        script.setScriptPayload(payload);
        scriptRepository.save(script);
        updateShotRows(script, updatedShots);
        int updatedPlanCount = updateShotPlans(
                script,
                updatedShots,
                overlayPlan,
                typography,
                videoDirectorPlan,
                referenceUrls,
                clientReview,
                dialogueLanguage,
                applyShotNumbers,
                acceptedReviewProposal
        );
        Map<String, Object> creativeLearning = creativeLearningService.recordApprovedReview(
                script,
                firstNonNull(
                        aiOutput.get("creativeLearnings"),
                        acceptedReviewProposal.get("creativeLearningCandidates")
                ),
                applyShotNumbers,
                tenantId,
                userId
        );
        clientReview.put("creativeLearning", creativeLearning);
        payload.put("creativeLearning", creativeLearning);
        payload.put("approvedCreativeLearnings", creativeLearning.getOrDefault("rules", List.of()));
        clientReview.put(
                "reviewChat",
                completeSelectedReviewInstruction(clientReview.get("reviewChat"), selectedReviewMessageId)
        );
        propagation = propagationSummary(updatedShots.size(), updatedPlanCount, dialogueLanguage);
        appendPlanningSnapshot(payload, planningSnapshot);
        propagation.put("undoAvailable", true);
        propagation.put("snapshotId", planningSnapshot.get("snapshotId"));
        propagation.put("snapshotCreatedAt", planningSnapshot.get("createdAt"));
        clientReview.put("propagation", propagation);
        payload.put("planningPropagation", propagation);
        payload.put(CLIENT_REVIEW_KEY, clientReview);
        script.setScriptPayload(payload);
        scriptRepository.save(script);
        return toResponse(script);
    }

    private void addClientReviewPackageMetadata(
            CreatorAiService.MeteredAiResponse aiResponse,
            int reviewRound
    ) {
        if (aiResponse == null || aiResponse.costMetadata() == null) {
            return;
        }
        try {
            aiResponse.costMetadata().put("packageCode", "AI_SHORT_STARTER_60");
            aiResponse.costMetadata().put("reviewRound", Math.max(1, reviewRound));
            aiResponse.costMetadata().put("includedReviewRoundLimit", 2);
            aiResponse.costMetadata().put("includedInPackage", reviewRound <= 2);
            aiResponse.costMetadata().put("packageReviewPolicy", "FIRST_TWO_APPLIED_CLIENT_REVIEWS_INCLUDED");
        } catch (UnsupportedOperationException ignored) {
            // Test/fallback providers may expose an immutable empty metadata map.
        }
    }

    @Transactional
    public StoryboardClientReviewResponse revertReview(
            UUID scriptId,
            String tenantId,
            String userId
    ) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Map<String, Object> payload = new LinkedHashMap<>(
                script.getScriptPayload() == null ? Map.of() : script.getScriptPayload()
        );
        String creativeLearningBatchId = stringValue(
                mapValue(mapValue(payload.get(CLIENT_REVIEW_KEY)).get("creativeLearning")).get("batchId")
        );
        List<Map<String, Object>> snapshots = copyMapList(payload.get(CLIENT_REVIEW_SNAPSHOTS_KEY));
        if (snapshots.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "There is no earlier client-review planning version to restore."
            );
        }

        Map<String, Object> snapshot = snapshots.remove(snapshots.size() - 1);
        restorePlanningSnapshot(script, payload, snapshot);
        creativeLearningService.deactivateApprovedBatch(
                script.getProjectId(),
                creativeLearningBatchId,
                tenantId,
                userId
        );
        if (snapshots.isEmpty()) {
            payload.remove(CLIENT_REVIEW_SNAPSHOTS_KEY);
        } else {
            payload.put(CLIENT_REVIEW_SNAPSHOTS_KEY, snapshots);
        }

        Map<String, Object> restoredReview = new LinkedHashMap<>(mapValue(payload.get(CLIENT_REVIEW_KEY)));
        Map<String, Object> propagation = new LinkedHashMap<>();
        propagation.put("status", "REVERTED");
        propagation.put("revertedAt", OffsetDateTime.now().toString());
        propagation.put("revertedSnapshotId", snapshot.get("snapshotId"));
        propagation.put("undoAvailable", !snapshots.isEmpty());
        propagation.put("restoredDialogueLanguage", script.getDialogueLanguage());
        propagation.put("restoredVideoDirectorPlan", copyJsonValue(payload.get("videoDirectorPlan")));
        propagation.put("creativeLearningReverted", !creativeLearningBatchId.isBlank());
        propagation.put("message", "The planning document was restored without deleting the saved review working copy.");
        restoredReview.put("propagation", propagation);
        restoredReview.put("updatedAt", OffsetDateTime.now().toString());
        restoredReview.put("updatedBy", defaultString(userId, "anonymous"));
        payload.put(CLIENT_REVIEW_KEY, restoredReview);

        script.setScriptPayload(payload);
        scriptRepository.save(script);
        List<Map<String, Object>> restoredShots = copyMapList(script.getShots());
        restoreShotRows(script, restoredShots);
        restoreShotPlans(script, snapshot);
        return toResponse(script);
    }

    @Transactional(readOnly = true)
    public String buildAnimatedPreview(UUID scriptId, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        StoryboardClientReviewResponse review = toResponse(script);
        List<Map<String, Object>> shots = scriptShots(script);
        Map<Integer, PreviewAssets> assetsByShot = loadPreviewAssets(script);
        Map<Integer, Map<String, Object>> overlaysByShot = mapsByShotNumber(review.overlayPlan());
        Map<String, Object> typography = review.typographySystem();

        List<String> slides = new ArrayList<>();
        List<String> rail = new ArrayList<>();
        for (int index = 0; index < shots.size(); index++) {
            Map<String, Object> shot = shots.get(index);
            int shotNumber = intValue(shot.get("shotNumber"), index + 1);
            PreviewAssets assets = assetsByShot.getOrDefault(shotNumber, new PreviewAssets());
            String title = firstNonBlank(shot.get("title"), shot.get("shotTitle"), "Shot " + shotNumber);
            String visual = firstNonBlank(
                    shot.get("visualDirection"),
                    shot.get("visual"),
                    shot.get("description"),
                    shot.get("action"),
                    shot.get("purpose")
            );
            String dialogue = plainText(firstNonNull(
                    shot.get("dialogue"),
                    shot.get("primaryDialogue"),
                    shot.get("voiceOver")
            ));
            String camera = joinNonBlank(" · ",
                    stringValue(shot.get("shotType")),
                    stringValue(shot.get("cameraAngle")),
                    stringValue(shot.get("cameraMovement"))
            );
            String heroUrl = firstNonBlank(
                    assets.productionUrl,
                    assets.storyboardUrl,
                    assets.cameraUrl,
                    assets.lightingUrl
            );
            String frameLabel = !assets.productionUrl.isBlank() ? "Production frame" : "Storyboard frame";
            long sceneMs = sceneDurationMs(shot);
            String timestamp = timestamp(shot, sceneMs);
            Map<String, Object> overlay = overlaysByShot.getOrDefault(
                    shotNumber,
                    mapValue(firstNonNull(shot.get("overlayPlan"), shot.get("overlay_plan")))
            );

            slides.add("""
                    <article class="slide%s" data-duration="%d" style="--scene-ms:%dms">
                      <div class="visual">
                        %s
                        <span class="frame-label">%s</span>
                        %s
                      </div>
                      <aside class="detail">
                        <span class="shot-no">Shot %d</span>
                        <h2>%s</h2>
                        <div class="time">%s</div>
                        <p class="story">%s</p>
                        <div class="specs">%s%s%s</div>
                      </aside>
                    </article>
                    """.formatted(
                    index == 0 ? " active" : "",
                    sceneMs,
                    sceneMs,
                    heroUrl.isBlank()
                            ? "<div class=\"fallback\">Visual pending</div>"
                            : "<img src=\"" + html(heroUrl) + "\" alt=\"" + html(title) + "\" />",
                    html(frameLabel),
                    previewOverlay(overlay, typography, sceneMs)
                            + (dialogue.isBlank()
                            ? "" : "<div class=\"caption\">" + html(dialogue) + "</div>"),
                    shotNumber,
                    html(title),
                    html(timestamp),
                    html(defaultString(visual, "Visual direction is being refined.")),
                    spec("Camera", camera),
                    spec("Dialogue / VO", defaultString(dialogue, "No spoken line")),
                    spec("Language", review.dialogueLanguage())
            ));
            rail.add("""
                    <button type="button" class="%s" data-slide="%d">
                      <span class="thumb">%s</span>
                      <span><strong>%s</strong><small>Shot %d · %s</small></span>
                    </button>
                    """.formatted(
                    index == 0 ? "active" : "",
                    index,
                    heroUrl.isBlank() ? String.valueOf(shotNumber) : "<img src=\"" + html(heroUrl) + "\" alt=\"\" />",
                    html(title),
                    shotNumber,
                    html(timestamp)
            ));
        }

        Map<String, Object> payload = script.getScriptPayload() == null ? Map.of() : script.getScriptPayload();
        String storyline = firstNonBlank(payload.get("storyline"), payload.get("logline"), script.getScriptText());
        String reviewCards = reviewCards(review);
        String template = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width,initial-scale=1" />
                  <title>__TITLE__ — Animated Storyboard</title>
                  <link rel="preconnect" href="https://fonts.googleapis.com" />
                  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
                  <link href="https://fonts.googleapis.com/css2?family=Bebas+Neue&amp;family=DM+Sans:wght@500;600;700;800;900&amp;family=Inter:wght@500;600;700;800;900&amp;family=Lora:wght@500;600;700&amp;family=Montserrat:wght@600;700;800;900&amp;family=Oswald:wght@500;600;700&amp;family=Playfair+Display:wght@600;700;800&amp;family=Poppins:wght@500;600;700;800;900&amp;family=Raleway:wght@500;600;700;800;900&amp;family=Roboto+Slab:wght@500;600;700;800&amp;display=swap" rel="stylesheet" />
                  <style>
                    :root{color-scheme:dark;--gold:#f7c948}*{box-sizing:border-box}html,body{min-height:100%;margin:0;background:#05070d;color:#f8fafc;font-family:Inter,system-ui,sans-serif}
                    body{background:radial-gradient(circle at 12% 0%,rgba(247,201,72,.17),transparent 28%),radial-gradient(circle at 92% 12%,rgba(34,211,238,.12),transparent 25%),linear-gradient(145deg,#060910,#111827)}
                    button{font:inherit}.shell{width:min(1500px,100%);min-height:100vh;margin:auto;padding:22px;display:grid;grid-template-rows:auto 1fr auto;gap:16px}
                    header{display:flex;justify-content:space-between;gap:18px}.brand{display:flex;align-items:center;gap:12px}.mark{width:42px;height:42px;display:grid;place-items:center;border-radius:12px;background:linear-gradient(145deg,#ffe486,#d49d18);color:#07101f;font-weight:950}
                    .eyebrow,.shot-no{margin:0;color:var(--gold);font-size:10px;font-weight:900;text-transform:uppercase;letter-spacing:.15em}h1{margin:3px 0 0;font-size:clamp(20px,3vw,38px)}.meta{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px}.pill{height:max-content;border:1px solid #ffffff1f;border-radius:999px;padding:7px 10px;background:#ffffff0e;font-size:10px;font-weight:850;text-transform:uppercase}
                    .stage{position:relative;min-height:min(75vh,820px);overflow:hidden;border:1px solid #ffffff21;border-radius:20px;background:#02040a;box-shadow:0 30px 90px #0008}.progress{position:absolute;z-index:20;inset:0 0 auto;height:4px;background:#ffffff1f}.progress span{display:block;width:0;height:100%;background:linear-gradient(90deg,var(--gold),#22d3ee)}
                    .slide{position:absolute;inset:0;display:grid;grid-template-columns:minmax(0,1.55fr) minmax(320px,.75fr);opacity:0;visibility:hidden;transform:scale(1.015);transition:opacity .7s,transform 1s,visibility .7s}.slide.active{opacity:1;visibility:visible;transform:scale(1)}
                    .visual{position:relative;min-height:520px;overflow:hidden;background:#06080f}.visual img{width:100%;height:100%;object-fit:contain;background:#04060b}.slide.active .visual img{animation:kenburns var(--scene-ms) ease-in-out both}@keyframes kenburns{from{transform:scale(1.02) translate(-.4%,.2%)}to{transform:scale(1.095) translate(.8%,-.6%)}}
                    .fallback{height:100%;min-height:520px;display:grid;place-items:center;color:#73829a;font-weight:850}.frame-label{position:absolute;top:18px;left:18px;padding:8px 11px;border:1px solid #ffffff2e;border-radius:999px;background:#03060cb8;font-size:10px;font-weight:900;text-transform:uppercase}.caption{position:absolute;left:7%;right:7%;bottom:7%;padding:12px 15px;border:1px solid #ffffff26;border-radius:12px;background:#03060cd9;text-align:center;font-size:clamp(13px,1.7vw,22px);font-weight:850}
                    .story-overlay{position:absolute;z-index:5;left:7%;right:7%;display:flex;pointer-events:none}.story-overlay.top{top:12%}.story-overlay.middle{top:42%}.story-overlay.bottom{bottom:15%}.story-overlay.left{justify-content:flex-start;text-align:left}.story-overlay.center{justify-content:center;text-align:center}.story-overlay.right{justify-content:flex-end;text-align:right}
                    .story-overlay span{display:block;max-width:82%;padding:.18em .34em;color:#fff;font-size:clamp(24px,4vw,var(--overlay-size,58px));line-height:1.02;letter-spacing:-.035em;text-shadow:0 3px 16px #000c;opacity:0}
                    .story-overlay.overlay-panel span{padding:.35em .48em;border:1px solid #ffffff24;border-radius:.12em;background:#080b12c9;backdrop-filter:blur(8px)}.story-overlay.overlay-black span{padding:.32em .46em;background:#030405e8}.story-overlay.overlay-badge span{padding:.28em .45em;border:2px solid #fff;background:#05070d30;text-transform:uppercase;letter-spacing:.045em}.story-overlay.overlay-highlight span{padding:.24em .42em;background:#f2d8a7;color:#14100a;text-shadow:none}.story-overlay.overlay-tag span{padding:.3em .48em;border-radius:.12em;background:#d8c9ee;color:#17121d;text-shadow:none}
                    .story-overlay.slide span{transform:translateY(28px)}.story-overlay.zoom span{transform:scale(.88)}.story-overlay.wipe span{clip-path:inset(0 100% 0 0)}
                    .slide.active .story-overlay span{animation:storyOverlayIn var(--overlay-in,650ms) cubic-bezier(.2,.8,.2,1) var(--overlay-delay,250ms) forwards,storyOverlayOut var(--overlay-out,450ms) ease var(--overlay-out-delay,5200ms) forwards}
                    @keyframes storyOverlayIn{to{opacity:1;transform:none;clip-path:inset(0 0 0 0)}}@keyframes storyOverlayOut{to{opacity:0;transform:translateY(-10px)}}
                    .detail{padding:clamp(22px,3vw,42px);display:flex;flex-direction:column;justify-content:center;background:linear-gradient(155deg,#161e31fa,#070a12fa);border-left:1px solid #ffffff17}.detail h2{margin:9px 0 0;font-size:clamp(25px,3vw,48px);line-height:1.01}.time{margin-top:12px;color:#9fb0c9;font-size:12px;font-weight:800}.story{margin:22px 0 0;color:#dce5f3;font-size:14px;font-weight:650;line-height:1.65}.specs{display:grid;gap:9px;margin-top:22px}.spec{padding:10px 12px;border:1px solid #ffffff1a;border-radius:10px;background:#ffffff0b}.spec span{color:#8392aa;font-size:9px;font-weight:900;text-transform:uppercase}.spec p{margin:4px 0 0;font-size:11px;font-weight:700}
                    .controls{position:absolute;z-index:30;left:18px;bottom:18px;display:flex;gap:8px}.control{width:42px;height:42px;border:1px solid #ffffff2e;border-radius:50%;background:#03060cc7;color:white;cursor:pointer}.rail{display:flex;gap:9px;overflow:auto;padding-bottom:8px}.rail button{flex:0 0 155px;display:grid;grid-template-columns:42px 1fr;align-items:center;gap:9px;padding:7px;border:1px solid #ffffff1a;border-radius:11px;background:#ffffff0b;color:#d9e3f2;text-align:left;cursor:pointer}.rail button.active{border-color:#f7c948a6;background:#f7c9481a}.thumb{width:42px;height:42px;display:grid;place-items:center;overflow:hidden;border-radius:8px;background:#070a12}.thumb img{width:100%;height:100%;object-fit:cover}.rail strong,.rail small{display:block;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.rail strong{font-size:10px}.rail small{margin-top:3px;color:#7f8ea6;font-size:9px}
                    .intro{position:fixed;z-index:50;inset:0;display:grid;place-items:center;padding:24px;background:#02040aed;backdrop-filter:blur(18px);transition:.5s}.intro.hidden{opacity:0;visibility:hidden}.intro-card{width:min(820px,100%);padding:clamp(25px,5vw,58px);border:1px solid #ffffff26;border-radius:24px;background:linear-gradient(145deg,#1c263dfa,#080c16fa)}.intro h2{margin:10px 0 0;font-size:clamp(36px,7vw,74px);text-transform:uppercase;line-height:1}.logline{color:#cbd6e7;font-size:14px;line-height:1.65}.reviews{display:grid;grid-template-columns:repeat(3,1fr);gap:9px;margin-top:22px}.review{padding:11px;border:1px solid #ffffff1a;border-radius:11px;background:#ffffff0b}.review span{color:#f9d96d;font-size:9px;font-weight:900;text-transform:uppercase}.review p{margin:5px 0 0;color:#dce6f5;font-size:10px;line-height:1.45}.start{margin-top:24px;min-height:45px;border:0;border-radius:999px;padding:0 20px;background:linear-gradient(145deg,#ffe486,#d49d18);color:#07101f;font-weight:950;cursor:pointer}
                    @media(max-width:880px){.shell{padding:12px}header{display:block}.meta{justify-content:flex-start;margin-top:10px}.stage{min-height:790px}.slide{grid-template-columns:1fr;grid-template-rows:minmax(390px,55vh) auto}.detail{justify-content:flex-start;border-left:0;border-top:1px solid #ffffff17;padding:20px}.visual,.fallback{min-height:390px}.reviews{grid-template-columns:1fr}}
                  </style>
                </head>
                <body>
                  <div class="intro" id="intro"><div class="intro-card"><p class="eyebrow">DalaiLlama Creator · Client presentation</p><h2>__TITLE__</h2><p class="logline">__STORYLINE__</p>__REVIEWS__<button class="start" id="start">Play storyboard →</button></div></div>
                  <main class="shell">
                    <header><div class="brand"><div class="mark">DL</div><div><p class="eyebrow">Animated storyboard</p><h1>__TITLE__</h1></div></div><div class="meta"><span class="pill">__LANGUAGE__ dialogue</span><span class="pill">__FORMAT__</span><span class="pill">__SHOT_COUNT__ shots</span></div></header>
                    <section class="stage"><div class="progress"><span id="progress"></span></div>__SLIDES__<div class="controls"><button class="control" id="previous">←</button><button class="control" id="toggle">❚❚</button><button class="control" id="next">→</button></div></section>
                    <nav class="rail">__RAIL__</nav>
                  </main>
                  <script>
                    (()=>{const slides=[...document.querySelectorAll('.slide')],buttons=[...document.querySelectorAll('.rail button')],progress=document.getElementById('progress'),toggle=document.getElementById('toggle');let active=0,playing=true,timer=0,started=0;
                    const duration=()=>Number(slides[active]?.dataset.duration||6000);function animate(){if(!playing)return;const f=Math.min(1,(performance.now()-started)/duration());progress.style.width=(f*100)+'%';if(f<1)requestAnimationFrame(animate)}
                    function show(i){if(!slides.length)return;active=(i+slides.length)%slides.length;slides.forEach((s,n)=>s.classList.toggle('active',n===active));buttons.forEach((b,n)=>b.classList.toggle('active',n===active));buttons[active]?.scrollIntoView({behavior:'smooth',block:'nearest',inline:'center'});clearTimeout(timer);progress.style.width='0%';started=performance.now();if(playing){timer=setTimeout(()=>show(active+1),duration());requestAnimationFrame(animate)}}
                    function play(next){playing=next;toggle.textContent=playing?'❚❚':'▶';show(active)}document.getElementById('start').onclick=()=>{document.getElementById('intro').classList.add('hidden');show(0)};document.getElementById('previous').onclick=()=>show(active-1);document.getElementById('next').onclick=()=>show(active+1);toggle.onclick=()=>play(!playing);buttons.forEach((b,i)=>b.onclick=()=>show(i));document.onkeydown=e=>{if(e.key==='ArrowLeft')show(active-1);if(e.key==='ArrowRight')show(active+1);if(e.key===' '){e.preventDefault();play(!playing)}};show(0)})();
                  </script>
                </body>
                </html>
                """;

        return template
                .replace("__TITLE__", html(defaultString(script.getTitle(), "Storyboard")))
                .replace("__STORYLINE__", html(defaultString(storyline, "A visual walkthrough of the planned story and production frames.")))
                .replace("__REVIEWS__", reviewCards)
                .replace("__LANGUAGE__", html(defaultString(review.dialogueLanguage(), "English")))
                .replace("__FORMAT__", html(defaultString(script.getScreenType(), "vertical")))
                .replace("__SHOT_COUNT__", String.valueOf(shots.size()))
                .replace("__SLIDES__", String.join("\n", slides))
                .replace("__RAIL__", String.join("\n", rail));
    }

    private List<Map<String, Object>> applyAiShotChanges(
            List<Map<String, Object>> currentShots,
            List<Map<String, Object>> aiShots,
            List<Map<String, Object>> overlayPlan,
            Map<String, Object> typography,
            List<String> referenceUrls,
            Map<String, Object> clientReview,
            String dialogueLanguage
    ) {
        Map<Integer, Map<String, Object>> aiByShot = mapsByShotNumber(aiShots);
        Map<Integer, Map<String, Object>> overlayByShot = mapsByShotNumber(overlayPlan);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < currentShots.size(); index++) {
            Map<String, Object> current = new LinkedHashMap<>(currentShots.get(index));
            int shotNumber = intValue(current.get("shotNumber"), index + 1);
            Map<String, Object> revision = aiByShot.getOrDefault(shotNumber, Map.of());
            if (!revision.isEmpty()) current.putAll(revision);
            current.put("shotNumber", shotNumber);
            current.put("dialogueLanguage", dialogueLanguage);
            current.put("emojisAllowed", false);
            current.put("detailLevel", "production_ready");
            current.put("referenceUrls", referenceUrls);
            List<Map<String, Object>> shotVisualReferences = visualReferencesForShot(clientReview, shotNumber);
            String shotVisualReferenceUsageMode = visualReferenceUsageMode(shotVisualReferences);
            if (!shotVisualReferences.isEmpty()) {
                current.put("visualReferenceImages", shotVisualReferences);
                current.put(
                        "visualReferenceImageUrls",
                        visualReferenceUrls(shotVisualReferences)
                );
            }
            current.put("visualReferenceUsageMode", shotVisualReferenceUsageMode);
            current.put("typographySystem", typography);
            current.put("overlayPlan", overlayByShot.getOrDefault(
                    shotNumber,
                    defaultOverlayPlan(current, index, currentShots.size(), typography)
            ));
            current.put("clientFeedback", Map.of(
                    "storyboard", defaultString(stringValue(clientReview.get("storyboardFeedback")), DEFAULT_STORYBOARD_FEEDBACK),
                    "productionFrames", defaultString(stringValue(clientReview.get("productionFramesFeedback")), DEFAULT_PRODUCTION_FEEDBACK),
                    "dialogue", defaultString(stringValue(clientReview.get("dialogueFeedback")), DEFAULT_DIALOGUE_FEEDBACK)
            ));
            if (isChocolatePouringBeat(current)) {
                current.put("action", "Reveal the finished chocolate texture in a clean macro break, then show one piece lifted into the hero light. Do not show chocolate pouring.");
                current.put("visualDirection", "Use a distinct finished-product texture and consumption beat with crisp pack continuity, controlled highlights, and no pouring action.");
                current.put("revisionReason", "Client requested visual variety because the chocolate-pouring shot was repetitive.");
            }
            result.add(sanitizeMap(current));
        }
        return result;
    }

    private List<Map<String, Object>> overlayPlanForShots(
            List<Map<String, Object>> aiOverlayPlan,
            List<Map<String, Object>> reviewOverlayPlan,
            List<Map<String, Object>> shots,
            Map<String, Object> typography
    ) {
        String overlayPolicy = normalizeOverlayPolicy(typography.get("overlayPolicy"));
        Map<Integer, Map<String, Object>> aiByShot = mapsByShotNumber(aiOverlayPlan);
        Map<Integer, Map<String, Object>> reviewByShot = mapsByShotNumber(reviewOverlayPlan);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < shots.size(); index++) {
            Map<String, Object> shot = shots.get(index);
            int shotNumber = intValue(shot.get("shotNumber"), index + 1);
            Map<String, Object> fallback = defaultOverlayPlan(shot, index, shots.size(), typography);
            Map<String, Object> plan = new LinkedHashMap<>(fallback);
            plan.putAll(aiByShot.getOrDefault(shotNumber, Map.of()));
            Map<String, Object> reviewPlan = reviewByShot.getOrDefault(shotNumber, Map.of());
            if (Boolean.TRUE.equals(reviewPlan.get("locked"))) {
                plan.putAll(reviewPlan);
                plan.put("locked", true);
            }
            plan.put("shotNumber", shotNumber);
            if ("DISABLED".equals(overlayPolicy)) {
                plan.put("enabled", false);
                plan.put("text", "");
                plan.put("locked", false);
            }
            plan.put("text", stripEmoji(stringValue(plan.get("text"))));
            plan.put("copyRole", defaultString(
                    stringValue(plan.get("copyRole")),
                    index == 0 ? "Hook" : index == shots.size() - 1 ? "CTA" : "Benefit"
            ));
            plan.put("stylePresetId", defaultString(
                    stringValue(plan.get("stylePresetId")),
                    stringValue(typography.get("presetId"))
            ));
            plan.put("stylePresetName", defaultString(
                    stringValue(plan.get("stylePresetName")),
                    stringValue(typography.get("presetName"))
            ));
            plan.put("fontFamily", renderableFont(plan.get("fontFamily"), stringValue(typography.get("primaryFont"))));
            plan.put("fontWeight", intValue(plan.get("fontWeight"), 800));
            plan.put("fontSizePx", intValue(plan.get("fontSizePx"), 58));
            plan.put("position", defaultString(stringValue(plan.get("position")), "Lower safe zone"));
            plan.put("safeZone", defaultString(stringValue(plan.get("safeZone")), "Keep 10% inset from all mobile edges"));
            plan.put("entrance", defaultString(stringValue(plan.get("entrance")), "Slide up and fade"));
            plan.put("entranceDurationMs", intValue(plan.get("entranceDurationMs"), 650));
            plan.put("delayMs", intValue(plan.get("delayMs"), 250));
            plan.put("holdDurationMs", intValue(plan.get("holdDurationMs"), 1800));
            plan.put("exit", defaultString(stringValue(plan.get("exit")), "Fade"));
            plan.put("exitDurationMs", intValue(plan.get("exitDurationMs"), 450));
            plan.put("speed", defaultString(stringValue(plan.get("speed")), "Measured"));
            plan.put("backgroundStyle", defaultString(
                    stringValue(plan.get("backgroundStyle")),
                    stringValue(typography.get("backgroundStyle"))
            ));
            plan.put("textColor", defaultString(stringValue(plan.get("textColor")), "#FFFFFF"));
            plan.put("accentColor", defaultString(stringValue(plan.get("accentColor")), ""));
            plan.put("rationale", defaultString(
                    stringValue(plan.get("rationale")),
                    Boolean.FALSE.equals(plan.get("enabled"))
                            ? "This beat stays visual-only so the product image can breathe."
                            : "The overlay supports comprehension without covering the product focal point."
            ));
            plan.put("compositionRationale", defaultString(
                    stringValue(plan.get("compositionRationale")),
                    Boolean.FALSE.equals(plan.get("enabled"))
                            ? "Preserve the frame as a clean visual beat."
                            : "Use the available negative space and protect the product, logo, faces, and primary action."
            ));
            plan.put("transitionPrompt", defaultString(
                    stringValue(plan.get("transitionPrompt")),
                    Boolean.FALSE.equals(plan.get("enabled"))
                            ? "No typography transition."
                            : plan.get("entrance") + " in " + plan.get("entranceDurationMs") + "ms, hold for "
                                    + plan.get("holdDurationMs") + "ms, then " + plan.get("exit") + " out in "
                                    + plan.get("exitDurationMs") + "ms."
            ));
            plan.put("pacingRationale", defaultString(
                    stringValue(plan.get("pacingRationale")),
                    Boolean.FALSE.equals(plan.get("enabled"))
                            ? "No reading-time allocation is required."
                            : "Time the overlay for one comfortable read without delaying the product reveal."
            ));
            plan.put("finalVideoPromptClause", defaultString(
                    stringValue(plan.get("finalVideoPromptClause")),
                    Boolean.FALSE.equals(plan.get("enabled"))
                            ? "No on-screen promotional text in this shot."
                            : "Render only the approved copy at the planned safe-zone position and execute the planned entrance, hold, and exit timing."
            ));
            result.add(sanitizeMap(plan));
        }
        return result;
    }

    private Map<String, Object> defaultOverlayPlan(
            Map<String, Object> shot,
            int index,
            int shotCount,
            Map<String, Object> typography
    ) {
        String overlayPolicy = normalizeOverlayPolicy(typography.get("overlayPolicy"));
        boolean enabled = !"DISABLED".equals(overlayPolicy)
                && (index == 0 || index == shotCount - 1 || (shotCount > 4 && index == shotCount / 2));
        String shotText = stripEmoji(firstNonBlank(shot.get("textOverlay"), shot.get("title"), shot.get("shotTitle")));
        String text = enabled
                ? trimToLength(
                        !shotText.isBlank() ? shotText : (index == shotCount - 1 ? "See the difference" : "Crafted with intention"),
                        72,
                        ""
                )
                : "";
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("shotNumber", intValue(shot.get("shotNumber"), index + 1));
        plan.put("enabled", enabled);
        plan.put("text", text);
        plan.put("copyRole", index == 0 ? "Hook" : index == shotCount - 1 ? "CTA" : "Benefit");
        plan.put("stylePresetId", defaultString(stringValue(typography.get("presetId")), "SWISS_FMCG_SYSTEM"));
        plan.put("stylePresetName", defaultString(stringValue(typography.get("presetName")), "Swiss Premium FMCG"));
        plan.put("fontFamily", defaultString(stringValue(typography.get("primaryFont")), "Montserrat"));
        plan.put("fontWeight", intValue(typography.get("primaryWeight"), 800));
        plan.put("fontSizePx", index == 0 ? 64 : 52);
        plan.put("position", index == 0 ? "Upper safe zone" : "Lower safe zone");
        plan.put("safeZone", "Keep 10% inset from all mobile edges");
        plan.put("entrance", defaultString(
                stringValue(typography.get("defaultEntrance")),
                index == 0 ? "Wipe and fade" : "Slide up and fade"
        ));
        plan.put("entranceDurationMs", index == 0 ? 520 : 650);
        plan.put("delayMs", 250);
        plan.put("holdDurationMs", 1800);
        plan.put("exit", "Fade");
        plan.put("exitDurationMs", 450);
        plan.put("speed", defaultString(
                stringValue(typography.get("defaultSpeed")),
                index == 0 ? "Quick" : "Measured"
        ));
        plan.put("backgroundStyle", defaultString(stringValue(typography.get("backgroundStyle")), "No panel"));
        plan.put("textColor", "#FFFFFF");
        plan.put("accentColor", "");
        plan.put("compositionRationale", enabled
                ? "Place type in intentional negative space without covering the product, logo, faces, or the primary motion path."
                : "Keep this beat visual-only so the composition can breathe.");
        plan.put("transitionPrompt", enabled
                ? plan.get("entrance") + " in " + plan.get("entranceDurationMs") + "ms, hold for "
                        + plan.get("holdDurationMs") + "ms, then fade out in " + plan.get("exitDurationMs") + "ms."
                : "No typography transition.");
        plan.put("pacingRationale", enabled
                ? "Allow one comfortable read while preserving the reveal rhythm."
                : "No reading-time allocation is required.");
        plan.put("finalVideoPromptClause", enabled
                ? "Render only the approved copy in the selected safe zone and execute its exact entrance, hold, and exit timing."
                : "No on-screen promotional text in this shot.");
        return plan;
    }

    private Map<String, Object> typographySystem(Map<String, Object> source) {
        Map<String, Object> typography = new LinkedHashMap<>(source == null ? Map.of() : source);
        typography.put("overlayPolicy", normalizeOverlayPolicy(typography.get("overlayPolicy")));
        typography.put("presetId", defaultString(stringValue(typography.get("presetId")), "SWISS_FMCG_SYSTEM"));
        typography.put("presetName", defaultString(stringValue(typography.get("presetName")), "Swiss Premium FMCG"));
        typography.put("presetPrompt", defaultString(
                stringValue(typography.get("presetPrompt")),
                "Premium FMCG typography, Swiss editorial grid, bold geometric sans-serif, mixed weights, restrained uppercase, high contrast, elegant spacing, luxury food advertising."
        ));
        typography.put("primaryFont", renderableFont(typography.get("primaryFont"), "Inter"));
        typography.put("primaryWeight", intValue(typography.get("primaryWeight"), 800));
        typography.put("secondaryFont", renderableFont(typography.get("secondaryFont"), "Inter"));
        typography.put("secondaryWeight", intValue(typography.get("secondaryWeight"), 500));
        typography.put("fallbackStack", defaultString(stringValue(typography.get("fallbackStack")), "Arial, sans-serif"));
        typography.put("caseRule", defaultString(stringValue(typography.get("caseRule")), "Sentence case; uppercase only for short hooks"));
        typography.put("maxLines", intValue(typography.get("maxLines"), 2));
        typography.put("backgroundStyle", defaultString(stringValue(typography.get("backgroundStyle")), "No panel"));
        typography.put("defaultEntrance", defaultString(stringValue(typography.get("defaultEntrance")), "Wipe and fade"));
        typography.put("defaultSpeed", defaultString(stringValue(typography.get("defaultSpeed")), "Measured"));
        typography.put("editorAutonomy", booleanValue(typography.get("editorAutonomy"), true));
        typography.put("editorDecisionScope", defaultString(
                stringValue(typography.get("editorDecisionScope")),
                "AI editor decides selective copy, hierarchy, font approximation, safe placement, transition, and pacing per shot."
        ));
        typography.put("draftOnly", booleanValue(typography.get("draftOnly"), true));
        typography.put("fontReferenceUrls", FONT_REFERENCE_URLS);
        typography.put("decisionSource", defaultString(stringValue(typography.get("decisionSource")), "AI creative-direction decision"));
        return sanitizeMap(typography);
    }

    private String normalizeOverlayPolicy(Object value) {
        String policy = defaultString(stringValue(value), "AUTO").trim().toUpperCase(Locale.ROOT);
        return switch (policy) {
            case "ENABLED", "DISABLED" -> policy;
            default -> "AUTO";
        };
    }

    private void preserveClientTypographyChoice(
            Map<String, Object> candidate,
            Map<String, Object> draft
    ) {
        if (draft.containsKey("overlayPolicy")) {
            candidate.put("overlayPolicy", draft.get("overlayPolicy"));
        }
        String decisionSource = stringValue(draft.get("decisionSource")).toLowerCase(Locale.ROOT);
        boolean clientLocked = booleanValue(draft.get("locked"), false)
                || decisionSource.contains("client-approved")
                || decisionSource.contains("manual");
        if (!clientLocked) return;
        for (String key : List.of(
                "presetId",
                "presetName",
                "presetPrompt",
                "primaryFont",
                "secondaryFont",
                "primaryWeight",
                "secondaryWeight",
                "caseRule",
                "maxLines",
                "backgroundStyle",
                "defaultEntrance",
                "defaultSpeed"
        )) {
            if (draft.containsKey(key)) candidate.put(key, draft.get(key));
        }
    }

    private List<Map<String, Object>> typographyStyleCatalog() {
        return List.of(
                typographyPreset("SWISS_FMCG_SYSTEM", "Swiss Premium FMCG", "Default premium food and chocolate advertising system", "Inter", 800, "No panel", "Measured", "Swiss editorial grid, mixed geometric sans-serif weights, restrained uppercase, high contrast and elegant spacing."),
                typographyPreset("INGREDIENT_EMPHASIS", "Ingredient Emphasis", "Ingredient truth and provenance", "Inter", 800, "Matte black", "Measured", "Regular setup line with an extra-bold ingredient line, generous spacing and white type on matte black."),
                typographyPreset("NUTRITION_PANEL", "Nutrition Information Panel", "Short verified nutrition facts", "Oswald", 700, "Semi-transparent dark rectangle", "Quick", "Condensed white numerals and compact supporting copy in a translucent charcoal information panel."),
                typographyPreset("CLEAN_INGREDIENT_STATEMENT", "Clean Ingredient Statement", "Verified no-additive statements", "Inter", 750, "No panel", "Measured", "Minimal Helvetica-style editorial hierarchy with one clear statement per line."),
                typographyPreset("PRODUCT_NAME_BADGE", "Premium Product Badge", "Product or flavour reveal", "Bebas Neue", 700, "Thin outline badge", "Slow", "Uppercase condensed product name with precise tracking inside a thin rectangular keyline."),
                typographyPreset("BENEFIT_EMPHASIS", "Benefit Emphasis", "One verified benefit with restrained emphasis", "DM Sans", 800, "Matte black", "Measured", "Editorial benefit copy using mixed weights and one emphasized approved keyword."),
                typographyPreset("HERO_PRODUCT_TITLE", "Hero Product Title", "Opening hook or hero reveal", "Montserrat", 900, "No panel", "Quick", "Oversized geometric title with mixed scale and one approved accent-color word."),
                typographyPreset("PASTEL_HIGHLIGHT", "Pastel Highlight Label", "Flavour or editorial annotation", "Oswald", 700, "Pastel highlight strip", "Quick", "Bold condensed dark type on a restrained pastel editorial highlight strip."),
                typographyPreset("EDITORIAL_TAG", "Editorial Campaign Tag", "Campaign line or closing thought", "Poppins", 700, "Soft pastel tag box", "Slow", "Natural sentence-case campaign copy in a softly colored editorial tag.")
        );
    }

    private Map<String, Object> videoDirectorPlan(
            Object sourceValue,
            CreatorScript script,
            List<Map<String, Object>> shots,
            Map<String, Object> typography,
            List<Map<String, Object>> overlayPlan
    ) {
        Map<String, Object> source = mapValue(sourceValue);
        Map<Integer, Map<String, Object>> suppliedByShot = mapsByShotNumber(mapList(firstNonNull(
                source.get("shots"),
                source.get("shotByShot")
        )));
        Map<Integer, Map<String, Object>> overlaysByShot = mapsByShotNumber(overlayPlan);
        List<Map<String, Object>> normalizedShots = new ArrayList<>();
        double timeline = 0d;
        for (int index = 0; index < shots.size(); index++) {
            Map<String, Object> screenplayShot = shots.get(index);
            int shotNumber = intValue(screenplayShot.get("shotNumber"), index + 1);
            double fallbackDuration = Math.max(1d, doubleValue(screenplayShot.get("durationSeconds"), 4d));
            double start = doubleValue(
                    firstNonNull(screenplayShot.get("startTimeSeconds"), screenplayShot.get("startTime")),
                    timeline
            );
            if (start < timeline && index > 0) start = timeline;
            double end = doubleValue(
                    firstNonNull(screenplayShot.get("endTimeSeconds"), screenplayShot.get("endTime")),
                    start + fallbackDuration
            );
            if (end <= start) end = start + fallbackDuration;
            Map<String, Object> directorShot = defaultVideoDirectorShot(
                    script,
                    screenplayShot,
                    overlaysByShot.getOrDefault(shotNumber, Map.of()),
                    typography,
                    index,
                    shots.size(),
                    shotNumber,
                    start,
                    end
            );
            Map<String, Object> supplied = suppliedByShot.getOrDefault(shotNumber, Map.of());
            directorShot.putAll(supplied);
            directorShot.put("shotNumber", shotNumber);
            directorShot.put("startTimeSeconds", start);
            directorShot.put("endTimeSeconds", end);
            directorShot.put("durationSeconds", end - start);
            directorShot.put("productVisibilityPercent", Math.max(
                    0,
                    Math.min(100, intValue(directorShot.get("productVisibilityPercent"), visibilityForDirectorShot(index, shots.size())))
            ));
            String suppliedGenerationPrompt = defaultString(
                    stringValue(directorShot.get("generationPrompt")),
                    stringValue(defaultVideoDirectorShot(
                            script,
                            screenplayShot,
                            overlaysByShot.getOrDefault(shotNumber, Map.of()),
                            typography,
                            index,
                            shots.size(),
                            shotNumber,
                            start,
                            end
                    ).get("generationPrompt"))
            );
            String editorTypographyClause = editorTypographyPromptClause(
                    overlaysByShot.getOrDefault(shotNumber, Map.of()),
                    typography,
                    start,
                    end
            );
            directorShot.put(
                    "generationPrompt",
                    suppliedGenerationPrompt.contains("[EDITOR TYPOGRAPHY EXECUTION]")
                            ? suppliedGenerationPrompt
                            : suppliedGenerationPrompt + "\n\n" + editorTypographyClause
            );
            directorShot.put(
                    "perSecondFrames",
                    perSecondDirectorFrames(directorShot, mapList(supplied.get("perSecondFrames")), start, end)
            );
            normalizedShots.add(sanitizeMap(directorShot));
            timeline = end;
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("mode", "PRODUCT_SHOWCASE_LUXURY");
        plan.put("modeLabel", "Luxury product showcase");
        plan.put("conceptTitle", "Mystery first. Craft second. Hero product last.");
        plan.put("directingPrinciple", "Establish desire and a premium brand world before explaining ingredients or revealing the complete pack.");
        plan.put("openingRule", "The first 2-3 seconds sell desire, mystery, scale, and premium positioning. No ingredient explanation and no complete product reveal.");
        plan.put("revealArc", List.of(
                "Brand world / desire",
                "Artifact discovery",
                "Texture / craft",
                "Ingredients",
                "Hero product"
        ));
        plan.put("productIdentityPolicy", "Preserve the exact approved project product name, logo, packaging, claims, colors, proportions, and material details. References are inspiration only and must never replace project identity.");
        plan.put("cameraPhilosophy", "Never feel static: use slow dolly, macro slider, tiny orbit, push through shadow, rack focus, and motivated light sweeps.");
        plan.put("lightingPhilosophy", "Reveal only what the beat needs with warm amber rim light, thin edge light, black negative fill, gold reflections, volumetric haze, and restrained particles.");
        boolean horizontal = defaultString(script.getScreenType(), "vertical").toLowerCase(Locale.ROOT)
                .matches(".*(horizontal|landscape|16:9).*");
        String masteringResolution = horizontal
                ? "4K UHD 3840x2160 master minimum; 6K/8K cinema capture when supported"
                : "4K UHD 2160x3840 vertical master minimum; 6K/8K cinema capture when supported";
        plan.put("masteringResolution", masteringResolution);
        plan.put("captureStandard", "Professional commercial cinema capture: 10/12-bit log or RAW intent, controlled exposure, accurate color, stable geometry, and planned motion cadence.");
        plan.put("cameraDepartmentStandard", "Director of photography, camera operator, and focus-puller level decisions: cinema body, professional glass, measured focus marks, calibrated movement, and no rookie auto settings.");
        plan.put("lightingDepartmentStandard", "DP and gaffer-ready lighting: motivated key, shaped fill or negative fill, rim/separation, modifiers, color temperature, contrast ratio, reflection control, and shot-to-shot continuity.");
        plan.put("directionStandard", "Commercial director intent on every beat: dramatic purpose, reveal discipline, product choreography, eyeline or blocking when relevant, transition motivation, and exact end-state continuity.");
        plan.put("draftOnly", true);
        plan.putAll(source);
        plan.put("masteringResolution", masteringResolution);
        plan.put("shots", normalizedShots);
        plan.put("shotByShot", normalizedShots);
        plan.put("totalDurationSeconds", timeline);
        String generatedMasterPrompt = normalizedShots.stream()
                .map(item -> "Shot " + item.get("shotNumber") + " [" + item.get("startTimeSeconds") + "-"
                        + item.get("endTimeSeconds") + "s]: " + stringValue(item.get("generationPrompt")))
                .collect(Collectors.joining("\n\n"));
        List<String> perSecondPromptSegments = new ArrayList<>();
        for (Map<String, Object> directorShot : normalizedShots) {
            int directorShotNumber = intValue(directorShot.get("shotNumber"), 0);
            for (Map<String, Object> frame : mapList(directorShot.get("perSecondFrames"))) {
                perSecondPromptSegments.add(
                        "[" + frame.get("startTimeSeconds") + "-" + frame.get("endTimeSeconds") + "s]"
                                + " Shot " + directorShotNumber
                                + " | Frame: " + defaultString(stringValue(frame.get("frameDescription")), "Hold approved visual continuity.")
                                + " | Camera: " + defaultString(stringValue(frame.get("cameraAction")), "Follow the approved camera move.")
                                + " | Lighting: " + defaultString(stringValue(frame.get("lightingAction")), "Preserve the approved lighting plan.")
                                + " | Focus: " + defaultString(stringValue(frame.get("focusAction")), "Maintain the approved focus behavior.")
                                + " | Direction: " + defaultString(stringValue(frame.get("directorAction")), "Execute the approved dramatic intention.")
                                + " | Product visibility: " + intValue(
                                        frame.get("productVisibilityPercent"),
                                        intValue(directorShot.get("productVisibilityPercent"), 0)
                                ) + "%"
                                + " | Continuity: " + defaultString(
                                        stringValue(frame.get("continuityAnchor")),
                                        "Preserve exact product identity, geometry, screen direction, and prior-frame end state."
                                )
                                + " | Generation segment: " + defaultString(
                                        stringValue(frame.get("promptSegment")),
                                        stringValue(directorShot.get("generationPrompt"))
                                )
                );
            }
        }
        String perSecondVideoPrompt = String.join("\n\n", perSecondPromptSegments);
        plan.put("perSecondVideoPrompt", perSecondVideoPrompt);
        String masterPromptIntro = defaultString(
                stringValue(source.get("masterVideoPrompt")),
                "Create a premium product film using the exact approved project identity. Mystery first, craftsmanship second, full hero product last. "
                        + masteringResolution + ". Use a professional cinema-camera, DP, gaffer, focus-puller, and commercial-director standard; no rookie or casual camera, lighting, exposure, or direction. "
                        + "Do not introduce unapproved branding, claims, packaging, or text."
        );
        if (masterPromptIntro.contains("[MANDATORY SHOT AND EDITOR EXECUTION]")) {
            masterPromptIntro = masterPromptIntro.substring(
                    0,
                    masterPromptIntro.indexOf("[MANDATORY SHOT AND EDITOR EXECUTION]")
            ).trim();
        }
        plan.put(
                "masterVideoPrompt",
                masterPromptIntro
                        + "\n\n[MANDATORY SHOT AND EDITOR EXECUTION]\n" + generatedMasterPrompt
                        + "\n\n[SECOND-BY-SECOND COMPLETE AD EXECUTION]\n" + perSecondVideoPrompt
        );
        return sanitizeMap(plan);
    }

    private String editorTypographyPromptClause(
            Map<String, Object> overlay,
            Map<String, Object> typography,
            double shotStart,
            double shotEnd
    ) {
        if (overlay == null
                || overlay.isEmpty()
                || Boolean.FALSE.equals(overlay.get("enabled"))
                || stripEmoji(overlay.get("text")).isBlank()) {
            return "[EDITOR TYPOGRAPHY EXECUTION] No on-screen promotional text in this shot. "
                    + "Do not invent captions, claims, decorative lettering, emojis, or a CTA.";
        }
        String text = stripEmoji(overlay.get("text"));
        String font = defaultString(
                stringValue(overlay.get("fontFamily")),
                defaultString(stringValue(typography.get("primaryFont")), "Inter")
        );
        int weight = Math.max(400, Math.min(900, intValue(
                overlay.get("fontWeight"),
                intValue(typography.get("primaryWeight"), 800)
        )));
        int sizePx = Math.max(24, Math.min(120, intValue(overlay.get("fontSizePx"), 58)));
        int delayMs = Math.max(0, intValue(overlay.get("delayMs"), 250));
        int entranceMs = Math.max(150, intValue(overlay.get("entranceDurationMs"), 650));
        int holdMs = Math.max(500, intValue(overlay.get("holdDurationMs"), 1800));
        int exitMs = Math.max(150, intValue(overlay.get("exitDurationMs"), 450));
        double localIn = Math.min(Math.max(0d, shotEnd - shotStart), delayMs / 1000d);
        double localOut = Math.min(
                Math.max(localIn, shotEnd - shotStart),
                localIn + (entranceMs + holdMs + exitMs) / 1000d
        );
        return "[EDITOR TYPOGRAPHY EXECUTION] Render only the exact approved copy \"" + text + "\". "
                + "Use " + font + " or the closest production-safe visual match, weight " + weight + ", approximately "
                + sizePx + "px at delivery scale, " + defaultString(stringValue(overlay.get("backgroundStyle")), "No panel")
                + ", text color " + defaultString(stringValue(overlay.get("textColor")), "#FFFFFF") + ". "
                + "Place it in the " + defaultString(stringValue(overlay.get("position")), "Lower safe zone") + "; "
                + defaultString(stringValue(overlay.get("safeZone")), "keep 10% inset from all mobile edges") + ". "
                + "Protect the product, logo, faces, highlights, and primary motion path. "
                + "At shot-local " + "%.2f".formatted(localIn) + "s, execute "
                + defaultString(stringValue(overlay.get("entrance")), "Fade") + " over " + entranceMs
                + "ms; hold " + holdMs + "ms; execute "
                + defaultString(stringValue(overlay.get("exit")), "Fade") + " over " + exitMs
                + "ms, completing by shot-local " + "%.2f".formatted(localOut) + "s. "
                + defaultString(stringValue(overlay.get("transitionPrompt")), "") + " "
                + defaultString(stringValue(overlay.get("finalVideoPromptClause")), "")
                + " No additional words, invented claims, emojis, logos, or unapproved typography.";
    }

    private Map<String, Object> defaultVideoDirectorShot(
            CreatorScript script,
            Map<String, Object> screenplayShot,
            Map<String, Object> overlay,
            Map<String, Object> typography,
            int index,
            int shotCount,
            int shotNumber,
            double start,
            double end
    ) {
        String productName = defaultString(script.getTitle(), "the approved product");
        boolean horizontal = defaultString(script.getScreenType(), "vertical").toLowerCase(Locale.ROOT)
                .matches(".*(horizontal|landscape|16:9).*");
        String captureResolution = horizontal
                ? "4K UHD 3840x2160 delivery; oversample from 6K/8K when supported"
                : "4K UHD 2160x3840 vertical delivery; oversample from 6K/8K when supported";
        String cameraPackage = index == shotCount - 1
                ? "ARRI Alexa 35 / Sony Venice 2 class cinema body with premium 65-85mm product prime, geared head, remote focus"
                : index == 0
                ? "ARRI Alexa 35 / Sony Venice 2 class cinema body with premium 85mm prime on motion-control dolly or calibrated slider"
                : "ARRI Alexa 35 / Sony Venice 2 class cinema body with professional 100mm macro, macro slider, remote focus and calibrated marks";
        String captureSettings = "24fps base, 180-degree shutter, native-base ISO, 10/12-bit log or RAW intent, protected highlights, locked white balance; use 48/60/120fps only for an explicitly planned slow-motion action";
        String role = directorRole(index, shotCount);
        int visibility = visibilityForDirectorShot(index, shotCount);
        boolean opening = index == 0;
        boolean hero = index == shotCount - 1;
        String visualAction = firstNonBlank(
                screenplayShot.get("visualDirection"),
                screenplayShot.get("visualAction"),
                screenplayShot.get("action"),
                screenplayShot.get("description")
        );
        if (visualAction.isBlank()) {
            visualAction = opening
                    ? "Begin in near-black as an amber highlight discovers an abstract architectural silhouette without revealing the complete product."
                    : hero
                    ? "Reveal the complete approved product in a confident, minimal hero composition."
                    : "Discover one new material, texture, craft, or ingredient detail without repeating an earlier action.";
        }
        String cameraMovement = opening
                ? "Imperceptibly slow dolly-in with a tiny controlled orbit"
                : hero ? "Measured push-in resolving to a stable hero hold" : "Macro slider with a subtle parallax move";
        String lens = opening ? "85mm cinematic lens" : hero ? "65-85mm premium product lens" : "100mm macro";
        String focus = opening
                ? "Deep shadow with only the amber edge resolving; never reveal the full pack"
                : hero ? "Rack focus from one approved detail to the complete product, then lock" : "Extremely shallow depth of field with one precise rack-focus event";
        String lighting = opening
                ? "Near-black negative fill, faint amber rim, one thin edge highlight, restrained atmospheric particles"
                : hero ? "Controlled warm key, exact package-color separation, subtle bronze reflections, no blown highlights" : "A narrow amber light sweep reveals only the selected detail against black negative fill";
        String overlayDirection = Boolean.FALSE.equals(overlay.get("enabled"))
                ? "No on-screen text in this shot."
                : "On-screen text must be exactly \"" + stripEmoji(overlay.get("text")) + "\" using "
                        + defaultString(stringValue(overlay.get("fontFamily")), stringValue(typography.get("primaryFont")))
                        + " at the approved timing and safe-zone position.";
        String identityRule = "Preserve the exact approved identity of " + productName
                + ": name, logo, pack geometry, label, colors, claims, materials, scale, and proportions. "
                + "Reference imagery may inspire atmosphere, composition, lighting, texture, and pacing only.";
        String openingRule = opening
                ? "Do not show ingredients, a complete pack, or an explanatory label; product visibility must remain below 10 percent."
                : hero ? "This is the earned complete product reveal; show the approved pack clearly and confidently." : "Reveal only the portion required by this beat and preserve discovery.";
        String generationPrompt = String.join(" ",
                "Premium luxury product film, shot " + shotNumber + ", " + role + ", " + (end - start) + " seconds.",
                visualAction,
                "Camera: " + cameraMovement + ", " + lens + ".",
                "Professional capture: " + captureResolution + "; " + cameraPackage + "; " + captureSettings + ".",
                "Focus: " + focus + ".",
                "Lighting: " + lighting + ". Execute as a gaffer-ready setup with motivated key, shaped negative fill, controlled rim/separation, declared color temperature, contrast ratio, modifiers, flagging, reflection control, and continuity marks.",
                "Direction: execute the dramatic intention and product choreography deliberately; no generic, rookie, casual, or auto-camera interpretation.",
                "Product visibility: approximately " + visibility + " percent.",
                openingRule,
                overlayDirection,
                identityRule,
                "Physically plausible motion, stable geometry, restrained sound-design sync, no people, no random captions, no watermarks, and no repetitive pouring action."
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shotNumber", shotNumber);
        result.put("startTimeSeconds", start);
        result.put("endTimeSeconds", end);
        result.put("durationSeconds", end - start);
        result.put("role", role);
        result.put("objective", opening
                ? "Create desire and mystery before explanation."
                : hero ? "Deliver the earned complete product payoff." : "Reveal one new piece of craft or product information.");
        result.put("productVisibilityPercent", visibility);
        result.put("visualAction", visualAction);
        result.put("cameraMovement", cameraMovement);
        result.put("cameraAngle", opening ? "Monumental low three-quarter or abstract silhouette" : "Controlled detail angle");
        result.put("captureResolution", captureResolution);
        result.put("cameraPackage", cameraPackage);
        result.put("captureSettings", captureSettings);
        result.put("lens", lens);
        result.put("focusBehavior", focus);
        result.put("lighting", lighting);
        result.put("lightingPlan", "Motivated key plus shaped negative fill and precise rim/separation; set color temperature and contrast ratio, use flags/scrims/diffusion, control specular reflections, record intensity and position for continuity.");
        result.put("directorNotes", opening
                ? "Direct the audience to feel scale and desire before comprehension; protect the mystery and reveal less than 10 percent."
                : hero ? "Let the complete approved product reveal feel earned; hold long enough for confident recognition without over-selling." : "Reveal one new piece of information only; choreograph product, camera, focus, and light toward the next motivated transition.");
        result.put("environment", firstNonBlank(screenplayShot.get("environment"), screenplayShot.get("background"), "Infinite matte-black premium brand world"));
        result.put("transitionIn", index == 0 ? "Fade up from complete black" : "Continue the prior shot's light, texture, or motion vector");
        result.put("transitionOut", hero ? "Resolve into a confident end hold" : "Motivated match cut through shadow, reflection, texture, or shape");
        result.put("soundDesign", opening ? "Near-silence, low architectural tone, delicate material detail" : "Restrained tactile accent synchronized to the reveal");
        result.put("overlayPlan", overlay);
        result.put("generationPrompt", generationPrompt);
        return result;
    }

    private List<Map<String, Object>> perSecondDirectorFrames(
            Map<String, Object> shot,
            List<Map<String, Object>> suppliedFrames,
            double start,
            double end
    ) {
        List<Map<String, Object>> frames = new ArrayList<>();
        int frameCount = Math.max(1, (int) Math.ceil(end - start));
        for (int index = 0; index < frameCount; index++) {
            double frameStart = start + index;
            double frameEnd = Math.min(end, frameStart + 1d);
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("second", index + 1);
            frame.put("startTimeSeconds", frameStart);
            frame.put("endTimeSeconds", frameEnd);
            frame.put("frameDescription", index == 0
                    ? "Establish the shot's new visual information without giving away the next reveal."
                    : index == frameCount - 1
                    ? "Complete this beat and shape the light, focus, and motion into the planned transition."
                    : "Advance the discovery with one controlled change in scale, focus, material, or light.");
            frame.put("productVisibilityPercent", shot.get("productVisibilityPercent"));
            frame.put("captureStandard", joinNonBlank(" | ",
                    stringValue(shot.get("captureResolution")),
                    stringValue(shot.get("cameraPackage")),
                    stringValue(shot.get("captureSettings"))
            ));
            frame.put("cameraAction", shot.get("cameraMovement"));
            frame.put("lightingAction", shot.get("lighting"));
            frame.put("focusAction", shot.get("focusBehavior"));
            frame.put("directorAction", shot.get("directorNotes"));
            frame.put("continuityAnchor", "Preserve exact product geometry, identity, light direction, screen direction, and the prior frame's end state.");
            frame.put("promptSegment", stringValue(shot.get("generationPrompt")));
            if (index < suppliedFrames.size()) frame.putAll(suppliedFrames.get(index));
            frame.put("second", index + 1);
            frame.put("startTimeSeconds", frameStart);
            frame.put("endTimeSeconds", frameEnd);
            frames.add(sanitizeMap(frame));
        }
        return frames;
    }

    private List<Map<String, Object>> attachVideoDirectorPlan(
            List<Map<String, Object>> shots,
            Map<String, Object> videoDirectorPlan
    ) {
        Map<Integer, Map<String, Object>> directorByShot = mapsByShotNumber(mapList(videoDirectorPlan.get("shots")));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < shots.size(); index++) {
            Map<String, Object> shot = new LinkedHashMap<>(shots.get(index));
            int shotNumber = intValue(shot.get("shotNumber"), index + 1);
            Map<String, Object> directorShot = directorByShot.getOrDefault(shotNumber, Map.of());
            shot.put("videoDirectorPlan", directorShot);
            shot.put("videoPrompt", defaultString(
                    stringValue(directorShot.get("generationPrompt")),
                    stringValue(shot.get("videoPrompt"))
            ));
            shot.remove("masterVideoPrompt");
            result.add(sanitizeMap(shot));
        }
        return result;
    }

    private String directorRole(int index, int shotCount) {
        if (index == 0) return "Brand world / desire hook";
        if (index == shotCount - 1) return "Earned full product hero";
        if (index == 1) return "Artifact-like partial reveal";
        double progress = shotCount <= 1 ? 1d : (double) index / (double) (shotCount - 1);
        if (progress < 0.55d) return "Texture and craftsmanship";
        if (progress < 0.8d) return "Ingredient or product proof";
        return "Hero anticipation";
    }

    private int visibilityForDirectorShot(int index, int shotCount) {
        if (shotCount <= 1) return 100;
        if (index == 0) return 5;
        if (index == 1) return 15;
        if (index == shotCount - 1) return 100;
        double progress = (double) index / (double) (shotCount - 1);
        return Math.max(20, Math.min(80, (int) Math.round(progress * 85d)));
    }

    private Map<String, Object> typographyPreset(
            String id,
            String name,
            String useFor,
            String font,
            int weight,
            String background,
            String speed,
            String prompt
    ) {
        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("id", id);
        preset.put("name", name);
        preset.put("useFor", useFor);
        preset.put("primaryFont", font);
        preset.put("primaryWeight", weight);
        preset.put("backgroundStyle", background);
        preset.put("speed", speed);
        preset.put("prompt", prompt);
        return preset;
    }

    private String renderableFont(Object value, String fallback) {
        String requested = stringValue(value);
        return RENDERABLE_FONT_FAMILIES.stream()
                .filter(candidate -> candidate.equalsIgnoreCase(requested))
                .findFirst()
                .orElse(fallback);
    }

    private Map<String, Object> creativeDirection(
            Map<String, Object> aiOutput,
            Map<String, Object> clientReview,
            List<String> referenceUrls,
            String dialogueLanguage
    ) {
        Map<String, Object> direction = new LinkedHashMap<>(mapValue(aiOutput.get("creativeDirection")));
        direction.put("dialogueLanguage", dialogueLanguage);
        direction.put("emojisAllowed", false);
        direction.put("detailLevel", "production_ready");
        direction.put("referenceUrls", referenceUrls);
        direction.put("referenceStatus", referenceUrls.size() > FONT_REFERENCE_URLS.size()
                ? "SHARED"
                : "VISUAL_REFERENCES_REQUIRED");
        direction.put("visualVarietyRule", "Do not repeat chocolate-pouring imagery; use a distinct finished-product, texture, reaction, or consumption beat.");
        direction.put("frameFeedback", mapList(clientReview.get("frameFeedback")));
        return sanitizeMap(direction);
    }

    private Map<String, Object> propagationSummary(int updatedShotCount, int updatedPlanCount, String dialogueLanguage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "APPLIED");
        result.put("updatedShotCount", updatedShotCount);
        result.put("updatedPlanCount", updatedPlanCount);
        result.put("dialogueLanguage", dialogueLanguage);
        result.put("emojisAllowed", false);
        result.put("regenerationRequired", true);
        result.put("stages", List.of(
                "storyline",
                "screenplay",
                "shots",
                "storyboard",
                "production_frames",
                "lighting_plan",
                "camera_plan",
                "captions_and_overlays",
                "video_director_plan",
                "per_second_video_prompts",
                "video_handoff"
        ));
        result.put("appliedAt", OffsetDateTime.now().toString());
        return result;
    }

    /**
     * Package-private so {@link CreatorStoryboardWorkspaceService} can reuse the exact same
     * shot-row persistence path when merging a workspace back into the live script.
     */
    void updateShotRows(CreatorScript script, List<Map<String, Object>> updatedShots) {
        Map<Integer, Map<String, Object>> updatedByShot = mapsByShotNumber(updatedShots);
        List<CreatorScriptShot> rows = scriptShotRepository
                .findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(script.getId());
        for (CreatorScriptShot row : rows) {
            Map<String, Object> updated = updatedByShot.get(row.getShotNumber());
            if (updated == null) continue;
            row.setShotPayload(new LinkedHashMap<>(updated));
            String title = stringValue(updated.get("title"));
            if (!title.isBlank()) row.setTitle(trimToLength(title, 240, row.getTitle()));
            String purpose = stringValue(updated.get("purpose"));
            if (!purpose.isBlank()) row.setPurpose(purpose);
            Map<String, Object> dialogue = mapValue(updated.get("dialogue"));
            if (!dialogue.isEmpty()) row.setDialogue(dialogue);
        }
        if (!rows.isEmpty()) scriptShotRepository.saveAll(rows);
    }

    private void restoreShotRows(CreatorScript script, List<Map<String, Object>> restoredShots) {
        Map<Integer, Map<String, Object>> restoredByShot = mapsByShotNumber(restoredShots);
        List<CreatorScriptShot> rows = scriptShotRepository
                .findByScriptIdOrderBySequenceNumberAscSceneNumberAscShotNumberAsc(script.getId());
        for (CreatorScriptShot row : rows) {
            Map<String, Object> restored = restoredByShot.get(row.getShotNumber());
            if (restored == null) continue;
            row.setShotPayload(new LinkedHashMap<>(restored));
            row.setTitle(trimToLength(stringValue(restored.get("title")), 240, ""));
            row.setPurpose(stringValue(restored.get("purpose")));
            row.setDialogue(new LinkedHashMap<>(mapValue(restored.get("dialogue"))));
        }
        if (!rows.isEmpty()) scriptShotRepository.saveAll(rows);
    }

    private int updateShotPlans(
            CreatorScript script,
            List<Map<String, Object>> updatedShots,
            List<Map<String, Object>> overlayPlan,
            Map<String, Object> typography,
            Map<String, Object> videoDirectorPlan,
            List<String> referenceUrls,
            Map<String, Object> clientReview,
            String dialogueLanguage,
            List<Integer> applyShotNumbers,
            Map<String, Object> acceptedReviewProposal
    ) {
        Map<Integer, Map<String, Object>> shotsByNumber = mapsByShotNumber(updatedShots);
        Map<Integer, Map<String, Object>> overlaysByNumber = mapsByShotNumber(overlayPlan);
        Map<Integer, Map<String, Object>> directorShotsByNumber = mapsByShotNumber(
                mapList(videoDirectorPlan.get("shots"))
        );
        Set<Integer> affectedShotNumbers = new LinkedHashSet<>(applyShotNumbers == null ? List.of() : applyShotNumbers);
        Map<Integer, Map<String, Object>> acceptedRevisions = mapsByShotNumber(mapList(
                mapValue(acceptedReviewProposal).get("shotRevisions")
        ));
        Map<Integer, Map<String, Object>> acceptedPreviews = mapsByShotNumber(mapList(
                mapValue(acceptedReviewProposal).get("planningChangePreview")
        ));
        List<CreatorScriptShotPlan> plans = shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId());
        for (CreatorScriptShotPlan plan : plans) {
            int shotNumber = plan.getShotNumber() == null ? 0 : plan.getShotNumber();
            Map<String, Object> acceptedScope = new LinkedHashMap<>(
                    acceptedPreviews.getOrDefault(shotNumber, Map.of())
            );
            acceptedScope.putAll(acceptedRevisions.getOrDefault(shotNumber, Map.of()));
            boolean affected = affectedShotNumbers.contains(shotNumber);
            boolean productFrameRegenerationRequired = affected
                    && !Boolean.FALSE.equals(acceptedScope.get("productFrameChangeRequired"));
            boolean storyboardRegenerationRequired = affected
                    && (!Boolean.FALSE.equals(acceptedScope.get("storyboardChangeRequired"))
                    || productFrameRegenerationRequired);
            boolean frameRegenerationRequired = storyboardRegenerationRequired || productFrameRegenerationRequired;
            Map<String, Object> overlay = overlaysByNumber.getOrDefault(shotNumber, Map.of());
            Map<String, Object> shot = shotsByNumber.getOrDefault(shotNumber, Map.of());
            Map<String, Object> directorShot = directorShotsByNumber.getOrDefault(shotNumber, Map.of());
            List<Map<String, Object>> shotVisualReferences = copyMapList(mapList(shot.get("visualReferenceImages")));
            List<String> shotVisualReferenceUrls = visualReferenceUrls(shotVisualReferences);
            String shotVisualReferenceUsageMode = visualReferenceUsageMode(shotVisualReferences);

            Map<String, Object> storyboard = new LinkedHashMap<>(
                    plan.getStoryboardTag() == null ? Map.of() : plan.getStoryboardTag()
            );
            storyboard.put("overlayPlan", overlay);
            storyboard.put("typographySystem", typography);
            storyboard.put("videoDirectorPlan", directorShot);
            storyboard.put("textOverlay", Boolean.FALSE.equals(overlay.get("enabled")) ? "" : stringValue(overlay.get("text")));
            storyboard.put("dialogueLanguage", dialogueLanguage);
            storyboard.put("emojisAllowed", false);
            storyboard.put("referenceUrls", referenceUrls);
            storyboard.put("visualReferenceUsageMode", shotVisualReferenceUsageMode);
            storyboard.put("visualReferenceImages", shotVisualReferences);
            storyboard.put("visualReferenceImageUrls", shotVisualReferenceUrls);
            storyboard.put("frameFeedback", frameFeedbackForShot(clientReview, shotNumber));
            storyboard.put("perSecondFrames", directorShot.get("perSecondFrames"));
            storyboard.put("cameraPlan", directorShot.get("cameraMovement"));
            storyboard.put("lightingPlan", directorShot.get("lightingPlan"));
            storyboard.put("directionPlan", directorShot.get("directorNotes"));
            storyboard.put("transitionIn", directorShot.get("transitionIn"));
            storyboard.put("transitionOut", directorShot.get("transitionOut"));
            storyboard.put("productionDetailRequired", true);
            storyboard.put("regenerationRequired", frameRegenerationRequired);
            storyboard.put("storyboardRegenerationRequired", storyboardRegenerationRequired);
            storyboard.put("productFrameRegenerationRequired", productFrameRegenerationRequired);
            if (!shot.isEmpty()) {
                synchronizeStoryboardDialogue(storyboard, shot, dialogueLanguage);
                storyboard.put("revisedShot", shot);
            }
            plan.setStoryboardTag(storyboard);

            Map<String, Object> lighting = new LinkedHashMap<>(
                    plan.getLightingBuildSheetTag() == null ? Map.of() : plan.getLightingBuildSheetTag()
            );
            lighting.put("referenceUrls", referenceUrls);
            lighting.put("visualReferenceUsageMode", shotVisualReferenceUsageMode);
            lighting.put("visualReferenceImages", shotVisualReferences);
            lighting.put("visualReferenceImageUrls", shotVisualReferenceUrls);
            lighting.put("videoDirectorPlan", directorShot);
            lighting.put("lighting", directorShot.get("lighting"));
            lighting.put("lightingPlan", directorShot.get("lightingPlan"));
            lighting.put("directionPlan", directorShot.get("directorNotes"));
            lighting.put("transitionIn", directorShot.get("transitionIn"));
            lighting.put("transitionOut", directorShot.get("transitionOut"));
            lighting.put("departmentStandard", videoDirectorPlan.get("lightingDepartmentStandard"));
            lighting.put("clientReviewApplied", true);
            lighting.put("productionDetailRequired", true);
            lighting.put("regenerationRequired", frameRegenerationRequired);
            plan.setLightingBuildSheetTag(lighting);

            Map<String, Object> camera = new LinkedHashMap<>(
                    plan.getCameraPlanSheetTag() == null ? Map.of() : plan.getCameraPlanSheetTag()
            );
            camera.put("referenceUrls", referenceUrls);
            camera.put("visualReferenceUsageMode", shotVisualReferenceUsageMode);
            camera.put("visualReferenceImages", shotVisualReferences);
            camera.put("visualReferenceImageUrls", shotVisualReferenceUrls);
            camera.put("overlaySafeZone", overlay.get("safeZone"));
            camera.put("overlayPosition", overlay.get("position"));
            camera.put("videoDirectorPlan", directorShot);
            camera.put("cameraMovement", directorShot.get("cameraMovement"));
            camera.put("cameraAngle", directorShot.get("cameraAngle"));
            camera.put("cameraPackage", directorShot.get("cameraPackage"));
            camera.put("captureResolution", directorShot.get("captureResolution"));
            camera.put("captureSettings", directorShot.get("captureSettings"));
            camera.put("lens", directorShot.get("lens"));
            camera.put("focusBehavior", directorShot.get("focusBehavior"));
            camera.put("directionPlan", directorShot.get("directorNotes"));
            camera.put("transitionIn", directorShot.get("transitionIn"));
            camera.put("transitionOut", directorShot.get("transitionOut"));
            camera.put("departmentStandard", videoDirectorPlan.get("cameraDepartmentStandard"));
            camera.put("clientReviewApplied", true);
            camera.put("regenerationRequired", frameRegenerationRequired);
            plan.setCameraPlanSheetTag(camera);

            Map<String, Object> input = new LinkedHashMap<>(
                    plan.getInputPayload() == null ? Map.of() : plan.getInputPayload()
            );
            input.put("clientReview", compactClientReviewForShotPlan(clientReview, shotNumber));
            input.put("dialogueLanguage", dialogueLanguage);
            input.put("emojisAllowed", false);
            input.put("referenceUrls", referenceUrls);
            input.put("visualReferenceImages", shotVisualReferences);
            input.put("visualReferenceImageUrls", shotVisualReferenceUrls);
            input.put("visualReferenceUsageMode", shotVisualReferenceUsageMode);
            input.put("typographySystem", typography);
            input.put("overlayPlan", overlay);
            input.put("videoDirectorPlan", directorShot);
            input.remove("videoDirectorBlueprint");
            input.remove("masterVideoPrompt");
            input.remove("perSecondVideoPrompt");
            input.put("regenerationRequired", frameRegenerationRequired);
            input.put("storyboardRegenerationRequired", storyboardRegenerationRequired);
            input.put("productFrameRegenerationRequired", productFrameRegenerationRequired);
            plan.setInputPayload(input);
            if (affected) plan.setStatus("CHANGES_REQUESTED");
        }
        if (!plans.isEmpty()) shotPlanRepository.saveAll(plans);
        return plans.size();
    }

    private void synchronizeStoryboardDialogue(
            Map<String, Object> storyboard,
            Map<String, Object> shot,
            String dialogueLanguage
    ) {
        storyboard.put("dialogueLanguage", normalizeDialogueLanguage(dialogueLanguage, "English"));
        boolean primaryDialogueCopied = false;
        for (String key : DIALOGUE_LOCALIZATION_KEYS) {
            if (!shot.containsKey(key) || plainText(shot.get(key)).isBlank()) continue;
            storyboard.put(key, copyJsonValue(shot.get(key)));
            if ("primaryDialogue".equals(key)) primaryDialogueCopied = true;
        }
        if (!primaryDialogueCopied && hasDialogueContent(shot)) {
            // A stale planning-sheet primaryDialogue otherwise wins over the newly
            // translated canonical shot dialogue during storyboard rendering.
            storyboard.remove("primaryDialogue");
        }
    }

    private Map<String, Object> createPlanningSnapshot(
            CreatorScript script,
            Map<String, Object> payload,
            List<Map<String, Object>> shots
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshotId", UUID.randomUUID().toString());
        snapshot.put("createdAt", OffsetDateTime.now().toString());
        snapshot.put("scriptText", defaultString(script.getScriptText(), ""));
        snapshot.put("dialogueLanguage", defaultString(script.getDialogueLanguage(), "English"));
        snapshot.put("shots", compactSnapshotShots(shots, snapshot));

        Map<String, Object> planningPayload = new LinkedHashMap<>();
        List<String> presentKeys = new ArrayList<>();
        for (String key : REVERSIBLE_PLANNING_PAYLOAD_KEYS) {
            if (!payload.containsKey(key)) continue;
            presentKeys.add(key);
            planningPayload.put(key, copyJsonValue(payload.get(key)));
        }
        deduplicateSnapshotClientReview(planningPayload, snapshot);
        snapshot.put("planningPayloadKeys", presentKeys);
        snapshot.put("planningPayload", planningPayload);

        List<Map<String, Object>> planSnapshots = new ArrayList<>();
        for (CreatorScriptShotPlan plan : shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shotNumber", plan.getShotNumber());
            item.put("status", plan.getStatus());
            item.put("storyboardTag", copyJsonValue(plan.getStoryboardTag()));
            item.put("lightingBuildSheetTag", copyJsonValue(plan.getLightingBuildSheetTag()));
            item.put("cameraPlanSheetTag", copyJsonValue(plan.getCameraPlanSheetTag()));
            snapshotManagedInputPayload(item, plan.getInputPayload(), planningPayload);
            planSnapshots.add(item);
        }
        snapshot.put("shotPlans", planSnapshots);
        return snapshot;
    }

    private void appendPlanningSnapshot(
            Map<String, Object> payload,
            Map<String, Object> snapshot
    ) {
        List<Map<String, Object>> snapshots = copyMapList(payload.get(CLIENT_REVIEW_SNAPSHOTS_KEY)).stream()
                .map(this::compactPlanningSnapshot)
                .collect(Collectors.toCollection(ArrayList::new));
        snapshots.add(compactPlanningSnapshot(snapshot));
        while (snapshots.size() > MAX_CLIENT_REVIEW_SNAPSHOTS) snapshots.remove(0);
        payload.put(CLIENT_REVIEW_SNAPSHOTS_KEY, snapshots);
    }

    private void compactPlanningSnapshots(Map<String, Object> payload) {
        if (!payload.containsKey(CLIENT_REVIEW_SNAPSHOTS_KEY)) return;
        List<Map<String, Object>> snapshots = copyMapList(payload.get(CLIENT_REVIEW_SNAPSHOTS_KEY)).stream()
                .map(this::compactPlanningSnapshot)
                .collect(Collectors.toCollection(ArrayList::new));
        while (snapshots.size() > MAX_CLIENT_REVIEW_SNAPSHOTS) snapshots.remove(0);
        if (snapshots.isEmpty()) payload.remove(CLIENT_REVIEW_SNAPSHOTS_KEY);
        else payload.put(CLIENT_REVIEW_SNAPSHOTS_KEY, snapshots);
    }

    private void restorePlanningSnapshot(
            CreatorScript script,
            Map<String, Object> payload,
            Map<String, Object> snapshot
    ) {
        Map<String, Object> currentReview = new LinkedHashMap<>(mapValue(payload.get(CLIENT_REVIEW_KEY)));
        Set<String> presentKeys = new LinkedHashSet<>(stringList(snapshot.get("planningPayloadKeys")));
        Map<String, Object> planningPayload = restoredSnapshotPlanningPayload(snapshot);
        for (String key : REVERSIBLE_PLANNING_PAYLOAD_KEYS) {
            payload.remove(key);
            if (presentKeys.contains(key)) payload.put(key, copyJsonValue(planningPayload.get(key)));
        }
        if (Boolean.TRUE.equals(snapshot.get("clientReviewChatExcluded"))
                && presentKeys.contains(CLIENT_REVIEW_KEY)) {
            Map<String, Object> restoredReview = new LinkedHashMap<>(mapValue(payload.get(CLIENT_REVIEW_KEY)));
            if (currentReview.containsKey("reviewChat")) {
                restoredReview.put("reviewChat", copyJsonValue(currentReview.get("reviewChat")));
            }
            if (currentReview.containsKey("conversationMemory")) {
                restoredReview.put("conversationMemory", copyJsonValue(currentReview.get("conversationMemory")));
            }
            payload.put(CLIENT_REVIEW_KEY, restoredReview);
        }
        script.setScriptText(defaultString(stringValue(snapshot.get("scriptText")), ""));
        script.setDialogueLanguage(normalizeDialogueLanguage(snapshot.get("dialogueLanguage"), "English"));
        script.setShots(restoreSnapshotShots(snapshot));
    }

    private void restoreShotPlans(CreatorScript script, Map<String, Object> snapshot) {
        Map<Integer, Map<String, Object>> snapshotsByShot = mapsByShotNumber(mapList(snapshot.get("shotPlans")));
        List<CreatorScriptShotPlan> plans = shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId());
        for (CreatorScriptShotPlan plan : plans) {
            int shotNumber = plan.getShotNumber() == null ? 0 : plan.getShotNumber();
            Map<String, Object> stored = snapshotsByShot.get(shotNumber);
            if (stored == null) continue;
            plan.setStatus(defaultString(stringValue(stored.get("status")), "GENERATED"));
            plan.setStoryboardTag(new LinkedHashMap<>(mapValue(stored.get("storyboardTag"))));
            plan.setLightingBuildSheetTag(new LinkedHashMap<>(mapValue(stored.get("lightingBuildSheetTag"))));
            plan.setCameraPlanSheetTag(new LinkedHashMap<>(mapValue(stored.get("cameraPlanSheetTag"))));
            if (stored.containsKey("inputPayloadRestoreKeys")) {
                Map<String, Object> input = new LinkedHashMap<>(mapValue(plan.getInputPayload()));
                Map<String, Object> values = mapValue(stored.get("inputPayloadRestoreValues"));
                Map<String, Object> links = mapValue(stored.get("inputPayloadRestoreLinks"));
                for (String key : MANAGED_SHOT_PLAN_INPUT_KEYS) input.remove(key);
                for (String key : stringList(stored.get("inputPayloadRestoreKeys"))) {
                    Object restored = links.containsKey(key)
                            ? snapshotLinkedValue(stringValue(links.get(key)), snapshot)
                            : values.get(key);
                    input.put(key, copyJsonValue(restored));
                }
                plan.setInputPayload(input);
            } else {
                plan.setInputPayload(new LinkedHashMap<>(mapValue(stored.get("inputPayload"))));
            }
        }
        if (!plans.isEmpty()) shotPlanRepository.saveAll(plans);
    }

    private Map<String, Object> compactPlanningSnapshot(Map<String, Object> source) {
        Map<String, Object> snapshot = new LinkedHashMap<>(mapValue(copyJsonValue(source)));
        snapshot.put("shots", compactSnapshotShots(copyMapList(snapshot.get("shots")), snapshot));
        Map<String, Object> planningPayload = new LinkedHashMap<>(mapValue(snapshot.get("planningPayload")));
        deduplicateSnapshotClientReview(planningPayload, snapshot);
        snapshot.put("planningPayload", planningPayload);
        List<Map<String, Object>> compactPlans = new ArrayList<>();
        for (Map<String, Object> stored : mapList(snapshot.get("shotPlans"))) {
            Map<String, Object> plan = new LinkedHashMap<>(stored);
            if (!plan.containsKey("inputPayloadRestoreKeys")) {
                snapshotManagedInputPayload(plan, mapValue(plan.get("inputPayload")), planningPayload);
                plan.remove("inputPayload");
            }
            compactPlans.add(plan);
        }
        snapshot.put("shotPlans", compactPlans);
        return snapshot;
    }

    private List<Map<String, Object>> compactSnapshotShots(
            List<Map<String, Object>> shots,
            Map<String, Object> snapshot
    ) {
        String sharedPrompt = stringValue(snapshot.get("sharedShotMasterVideoPrompt"));
        Set<Integer> linkedShotNumbers = new LinkedHashSet<>(stringList(
                snapshot.get("sharedShotMasterVideoPromptShotNumbers")
        ).stream().map(value -> intValue(value, 0)).filter(value -> value > 0).toList());
        List<Map<String, Object>> compact = new ArrayList<>();
        for (int index = 0; index < shots.size(); index++) {
            Map<String, Object> shot = new LinkedHashMap<>(shots.get(index));
            int shotNumber = intValue(shot.get("shotNumber"), index + 1);
            String prompt = stringValue(shot.get("masterVideoPrompt"));
            if (!prompt.isBlank() && (sharedPrompt.isBlank() || sharedPrompt.equals(prompt))) {
                if (sharedPrompt.isBlank()) sharedPrompt = prompt;
                shot.remove("masterVideoPrompt");
                linkedShotNumbers.add(shotNumber);
            }
            compact.add(shot);
        }
        if (!sharedPrompt.isBlank()) {
            snapshot.put("sharedShotMasterVideoPrompt", sharedPrompt);
            snapshot.put("sharedShotMasterVideoPromptShotNumbers", linkedShotNumbers.stream().toList());
        }
        return compact;
    }

    private List<Map<String, Object>> restoreSnapshotShots(Map<String, Object> snapshot) {
        List<Map<String, Object>> shots = copyMapList(snapshot.get("shots"));
        String sharedPrompt = stringValue(snapshot.get("sharedShotMasterVideoPrompt"));
        Set<Integer> linkedShotNumbers = stringList(snapshot.get("sharedShotMasterVideoPromptShotNumbers")).stream()
                .map(value -> intValue(value, 0))
                .filter(value -> value > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (sharedPrompt.isBlank() || linkedShotNumbers.isEmpty()) return shots;
        for (int index = 0; index < shots.size(); index++) {
            Map<String, Object> shot = shots.get(index);
            if (linkedShotNumbers.contains(intValue(shot.get("shotNumber"), index + 1))) {
                shot.put("masterVideoPrompt", sharedPrompt);
            }
        }
        return shots;
    }

    private void deduplicateSnapshotClientReview(
            Map<String, Object> planningPayload,
            Map<String, Object> snapshot
    ) {
        Map<String, Object> review = new LinkedHashMap<>(mapValue(planningPayload.get(CLIENT_REVIEW_KEY)));
        if (review.containsKey("reviewChat") || review.containsKey("conversationMemory")) {
            review.remove("reviewChat");
            review.remove("conversationMemory");
            review.remove("updatedAt");
            review.remove("updatedBy");
            planningPayload.put(CLIENT_REVIEW_KEY, review);
            snapshot.put("clientReviewChatExcluded", true);
        }
        Object directorPlan = planningPayload.get("videoDirectorPlan");
        if (!review.isEmpty() && directorPlan != null && directorPlan.equals(review.get("videoDirectorPlan"))) {
            review.remove("videoDirectorPlan");
            planningPayload.put(CLIENT_REVIEW_KEY, review);
            snapshot.put("clientReviewVideoDirectorPlanLinked", true);
        }
    }

    private Map<String, Object> restoredSnapshotPlanningPayload(Map<String, Object> snapshot) {
        Map<String, Object> planningPayload = new LinkedHashMap<>(mapValue(snapshot.get("planningPayload")));
        if (Boolean.TRUE.equals(snapshot.get("clientReviewVideoDirectorPlanLinked"))) {
            Map<String, Object> review = new LinkedHashMap<>(mapValue(planningPayload.get(CLIENT_REVIEW_KEY)));
            review.put("videoDirectorPlan", copyJsonValue(planningPayload.get("videoDirectorPlan")));
            planningPayload.put(CLIENT_REVIEW_KEY, review);
        }
        return planningPayload;
    }

    private void snapshotManagedInputPayload(
            Map<String, Object> snapshotPlan,
            Map<String, Object> inputPayload,
            Map<String, Object> planningPayload
    ) {
        Map<String, Object> safeInputPayload = inputPayload == null ? Map.of() : inputPayload;
        List<String> presentKeys = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> links = new LinkedHashMap<>();
        for (String key : MANAGED_SHOT_PLAN_INPUT_KEYS) {
            if (!safeInputPayload.containsKey(key)) continue;
            presentKeys.add(key);
            Object value = safeInputPayload.get(key);
            String link = snapshotLinkForInputValue(key, value, planningPayload);
            if (link.isBlank()) values.put(key, copyJsonValue(value));
            else links.put(key, link);
        }
        snapshotPlan.put("inputPayloadRestoreKeys", presentKeys);
        snapshotPlan.put("inputPayloadRestoreValues", values);
        if (!links.isEmpty()) snapshotPlan.put("inputPayloadRestoreLinks", links);
    }

    private String snapshotLinkForInputValue(
            String key,
            Object value,
            Map<String, Object> planningPayload
    ) {
        Map<String, Object> review = new LinkedHashMap<>(mapValue(planningPayload.get(CLIENT_REVIEW_KEY)));
        if (!review.containsKey("videoDirectorPlan") && planningPayload.containsKey("videoDirectorPlan")) {
            review.put("videoDirectorPlan", planningPayload.get("videoDirectorPlan"));
        }
        Map<String, Object> director = mapValue(planningPayload.get("videoDirectorPlan"));
        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("clientReview", planningPayload.get(CLIENT_REVIEW_KEY));
        candidates.put("dialogueLanguage", planningPayload.get("dialogueLanguage"));
        candidates.put("referenceUrls", review.get("referenceUrls"));
        candidates.put("visualReferenceImages", review.get("visualReferenceImages"));
        candidates.put("visualReferenceImageUrls", planningPayload.get("visualInspirationReferenceImageUrls"));
        candidates.put("visualReferenceUsageMode", review.get("referenceUsageMode"));
        candidates.put("typographySystem", planningPayload.get("typographySystem"));
        candidates.put("videoDirectorBlueprint", planningPayload.get("videoDirectorPlan"));
        candidates.put("masterVideoPrompt", director.get("masterVideoPrompt"));
        candidates.put("perSecondVideoPrompt", director.get("perSecondVideoPrompt"));
        Object candidate = candidates.get(key);
        return candidate != null && candidate.equals(value) ? key : "";
    }

    private Object snapshotLinkedValue(String link, Map<String, Object> snapshot) {
        Map<String, Object> planningPayload = restoredSnapshotPlanningPayload(snapshot);
        Map<String, Object> review = mapValue(planningPayload.get(CLIENT_REVIEW_KEY));
        Map<String, Object> director = mapValue(planningPayload.get("videoDirectorPlan"));
        return switch (link) {
            case "clientReview" -> planningPayload.get(CLIENT_REVIEW_KEY);
            case "dialogueLanguage" -> planningPayload.get("dialogueLanguage");
            case "referenceUrls" -> review.get("referenceUrls");
            case "visualReferenceImages" -> review.get("visualReferenceImages");
            case "visualReferenceImageUrls" -> planningPayload.get("visualInspirationReferenceImageUrls");
            case "visualReferenceUsageMode" -> review.get("referenceUsageMode");
            case "typographySystem" -> planningPayload.get("typographySystem");
            case "videoDirectorBlueprint" -> planningPayload.get("videoDirectorPlan");
            case "masterVideoPrompt" -> director.get("masterVideoPrompt");
            case "perSecondVideoPrompt" -> director.get("perSecondVideoPrompt");
            default -> null;
        };
    }

    private Object copyJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), copyJsonValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::copyJsonValue).toList();
        }
        return value;
    }

    private List<Map<String, Object>> frameFeedbackForShot(Map<String, Object> clientReview, int shotNumber) {
        return mapList(clientReview.get("frameFeedback")).stream()
                .filter(item -> {
                    int targetShot = intValue(item.get("shotNumber"), 0);
                    return targetShot <= 0 || targetShot == shotNumber;
                })
                .toList();
    }

    private List<Map<String, Object>> visualReferencesForShot(
            Map<String, Object> clientReview,
            int shotNumber
    ) {
        Map<String, String> assignedAssetModes = new LinkedHashMap<>();
        for (Map<String, Object> item : latestActiveReviewInstructions(clientReview)) {
            if (isActiveReviewInstruction(item) && reviewMessageTargetsShot(item, shotNumber)) {
                collectVisualReferenceAssignments(assignedAssetModes, item);
            }
        }
        if (assignedAssetModes.isEmpty()) return List.of();
        return mapList(clientReview.get("visualReferenceImages")).stream()
                .filter(asset -> assignedAssetModes.containsKey(stringValue(asset.get("assetId"))))
                .<Map<String, Object>>map(asset -> {
                    Map<String, Object> assigned = new LinkedHashMap<>(asset);
                    String mode = assignedAssetModes.get(stringValue(asset.get("assetId")));
                    assigned.put("usageMode", mode);
                    assigned.put("visualReferenceUsageMode", mode);
                    assigned.put("referenceRole", "EXACT_SOURCE".equals(mode) ? "exact_visual_source" : "visual_inspiration_only");
                    assigned.put("assetRole", assigned.get("referenceRole"));
                    return assigned;
                })
                .limit(MAX_VISUAL_REFERENCE_IMAGES)
                .toList();
    }

    private List<Map<String, Object>> activeVisualReferenceImages(
            Map<String, Object> clientReview,
            List<Map<String, Object>> referenceHistory,
            String selectedReviewMessageId
    ) {
        Map<String, String> assignedAssetModes = new LinkedHashMap<>();
        for (Map<String, Object> item : selectedActiveReviewInstructions(
                clientReview,
                selectedReviewMessageId
        )) {
            if (isActiveReviewInstruction(item)) {
                collectVisualReferenceAssignments(assignedAssetModes, item);
            }
        }
        if (assignedAssetModes.isEmpty()) return List.of();
        return referenceHistory.stream()
                .filter(asset -> assignedAssetModes.containsKey(stringValue(asset.get("assetId"))))
                .<Map<String, Object>>map(asset -> {
                    Map<String, Object> active = new LinkedHashMap<>(asset);
                    String mode = assignedAssetModes.get(stringValue(asset.get("assetId")));
                    active.put("usageMode", mode);
                    active.put("visualReferenceUsageMode", mode);
                    active.put("referenceRole", "EXACT_SOURCE".equals(mode)
                            ? "exact_visual_source"
                            : "visual_inspiration_only");
                    active.put("assetRole", active.get("referenceRole"));
                    return active;
                })
                .limit(MAX_VISUAL_REFERENCE_IMAGES)
                .toList();
    }

    private boolean isActiveReviewInstruction(Map<String, Object> item) {
        if (!"user".equalsIgnoreCase(stringValue(item.get("role")))) return false;
        String status = stringValue(item.get("status")).toUpperCase(Locale.ROOT);
        return !"COMPLETED".equals(status) && !"FAILED".equals(status);
    }

    private List<Map<String, Object>> latestActiveReviewInstructions(Map<String, Object> clientReview) {
        List<Map<String, Object>> active = mapList(clientReview.get("reviewChat")).stream()
                .filter(this::isActiveReviewInstruction)
                .toList();
        return active.isEmpty() ? List.of() : List.of(active.get(active.size() - 1));
    }

    private List<Map<String, Object>> selectedActiveReviewInstructions(
            Map<String, Object> clientReview,
            String selectedReviewMessageId
    ) {
        String selectedId = defaultString(selectedReviewMessageId, "").trim();
        if (selectedId.isBlank()) return latestActiveReviewInstructions(clientReview);
        return mapList(clientReview.get("reviewChat")).stream()
                .filter(this::isActiveReviewInstruction)
                .filter(message -> selectedId.equals(stringValue(message.get("id"))))
                .limit(1)
                .toList();
    }

    private Map<String, Object> latestReviewProposalForAi(
            Map<String, Object> clientReview,
            String selectedReviewMessageId
    ) {
        List<Map<String, Object>> active = selectedActiveReviewInstructions(
                clientReview,
                selectedReviewMessageId
        );
        if (active.isEmpty()) return Map.of();
        String messageId = stringValue(active.get(0).get("id"));
        List<Map<String, Object>> chat = mapList(clientReview.get("reviewChat"));
        for (int index = chat.size() - 1; index >= 0; index--) {
            Map<String, Object> message = chat.get(index);
            if (!"assistant".equalsIgnoreCase(stringValue(message.get("role")))) continue;
            String confirmationId = firstNonBlank(
                    message.get("confirmationForMessageId"),
                    stringValue(message.get("id")).endsWith("-analysis")
                            ? stringValue(message.get("id")).substring(0, stringValue(message.get("id")).length() - 9)
                            : ""
            );
            if (!messageId.equals(confirmationId)) continue;
            Map<String, Object> proposal = mapValue(message.get("proposal"));
            if (proposal.isEmpty()) return Map.of();
            Map<String, Object> compact = new LinkedHashMap<>();
            List.of(
                    "targetType",
                    "shotNumber",
                    "affectedShotNumbers",
                    "continuityAnchorShotNumbers",
                    "changeSummary",
                    "imageRevisionPrompt",
                    "proposedShot",
                    "proposedOverlayPlan",
                    "shotRevisions",
                    "planningChangePreview",
                    "storyInterpretation",
                    "creativeLearningCandidates",
                    "requiresFrameRegeneration",
                    "affectedPlanningStages"
            ).forEach(key -> {
                if (proposal.containsKey(key)) compact.put(key, sanitizeValue(proposal.get(key)));
            });
            return compact;
        }
        return Map.of();
    }

    private List<Map<String, Object>> completeSelectedReviewInstruction(
            Object reviewChat,
            String selectedReviewMessageId
    ) {
        String completedAt = OffsetDateTime.now().toString();
        String selectedId = defaultString(selectedReviewMessageId, "").trim();
        List<Map<String, Object>> active = mapList(reviewChat).stream()
                .filter(this::isActiveReviewInstruction)
                .toList();
        String fallbackActiveId = active.isEmpty()
                ? ""
                : stringValue(active.get(active.size() - 1).get("id"));
        String completedMessageId = selectedId.isBlank() ? fallbackActiveId : selectedId;
        return mapList(reviewChat).stream()
                .map(source -> {
                    Map<String, Object> message = new LinkedHashMap<>(source);
                    if (isActiveReviewInstruction(message)
                            && completedMessageId.equals(stringValue(message.get("id")))) {
                        message.put("status", "COMPLETED");
                        message.put("completedAt", completedAt);
                    }
                    return message;
                })
                .toList();
    }

    private boolean reviewMessageTargetsShot(Map<String, Object> item, int shotNumber) {
        if (intValue(item.get("shotNumber"), 0) == shotNumber) return true;
        return stringList(item.get("affectedShotNumbers")).stream()
                .map(value -> intValue(value, 0))
                .anyMatch(value -> value == shotNumber);
    }

    private void collectVisualReferenceAssignments(Map<String, String> assignments, Map<String, Object> item) {
        String usageMode = normalizeVisualReferenceUsageMode(item.get("visualReferenceUsageMode"));
        stringList(item.get("visualReferenceAssetIds")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(assetId -> assignments.put(assetId, usageMode));
    }

    private String visualReferenceUsageMode(List<Map<String, Object>> references) {
        return references.stream().anyMatch(reference ->
                "EXACT_SOURCE".equals(normalizeVisualReferenceUsageMode(firstNonNull(
                        reference.get("visualReferenceUsageMode"),
                        reference.get("usageMode")
                )))
        ) ? "EXACT_SOURCE" : "INSPIRATION_ONLY";
    }

    private List<String> visualReferenceUrls(List<Map<String, Object>> references) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (Map<String, Object> reference : references) {
            String url = firstNonBlank(
                    reference.get("signedUrl"),
                    reference.get("publicUrl"),
                    reference.get("assetUrl"),
                    reference.get("url")
            );
            if (!url.isBlank()) urls.add(url);
        }
        return urls.stream().limit(MAX_VISUAL_REFERENCE_IMAGES).toList();
    }

    private Map<Integer, Map<String, Object>> mapsByShotNumber(List<Map<String, Object>> values) {
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            Map<String, Object> value = values.get(index);
            int shotNumber = intValue(value.get("shotNumber"), index + 1);
            result.put(shotNumber, new LinkedHashMap<>(value));
        }
        return result;
    }

    private List<Map<String, Object>> normalizeFrameFeedback(List<Map<String, Object>> values) {
        List<Map<String, Object>> result = copyMapList(values);
        if (result.isEmpty()) {
            result.add(new LinkedHashMap<>(Map.of(
                    "id", "client-chocolate-variety",
                    "targetType", "STORYBOARD_AND_PRODUCT",
                    "shotNumber", "",
                    "instruction", "Replace the repeated chocolate-pouring shot with a distinct product-detail or consumption beat while preserving continuity."
            )));
        }
        return result;
    }

    private void mergeIncomingReviewContext(
            Map<String, Object> review,
            Map<String, Object> incomingValue,
            CreatorScript script
    ) {
        Map<String, Object> incoming = mapValue(incomingValue);
        if (incoming.isEmpty()) return;
        review.put("storyboardFeedback", trimToLength(
                stringValue(incoming.get("storyboardFeedback")),
                6000,
                defaultString(stringValue(review.get("storyboardFeedback")), DEFAULT_STORYBOARD_FEEDBACK)
        ));
        review.put("productionFramesFeedback", trimToLength(
                stringValue(incoming.get("productionFramesFeedback")),
                6000,
                defaultString(stringValue(review.get("productionFramesFeedback")), DEFAULT_PRODUCTION_FEEDBACK)
        ));
        review.put("dialogueFeedback", trimToLength(
                stringValue(incoming.get("dialogueFeedback")),
                6000,
                defaultString(stringValue(review.get("dialogueFeedback")), DEFAULT_DIALOGUE_FEEDBACK)
        ));
        review.put("dialogueLanguage", normalizeDialogueLanguage(
                incoming.get("dialogueLanguage"),
                firstNonBlank(review.get("dialogueLanguage"), script.getDialogueLanguage())
        ));
        review.put("reviewStatus", normalizeStatus(firstNonBlank(incoming.get("reviewStatus"), review.get("reviewStatus"))));
        review.put("frameFeedback", normalizeFrameFeedback(mapList(incoming.get("frameFeedback"))));
        review.put("referenceUrls", normalizeReferenceUrls(stringList(incoming.get("referenceUrls"))));
        review.put("visualReferenceImages", normalizeVisualReferenceImages(incoming.get("visualReferenceImages"), script));
        review.put("referenceUsageMode", "INSPIRATION_ONLY");
        review.put("fontReferenceImages", normalizeFontReferenceImages(incoming.get("fontReferenceImages"), script));
        review.put("typographySystem", typographySystem(mapValue(incoming.get("typographySystem"))));
        review.put("overlayPlan", copyMapList(incoming.get("overlayPlan")));
        if (incoming.containsKey("videoDirectorPlan")) {
            review.put("videoDirectorPlan", videoDirectorPlan(
                    incoming.get("videoDirectorPlan"),
                    script,
                    scriptShots(script),
                    mapValue(review.get("typographySystem")),
                    mapList(review.get("overlayPlan"))
            ));
        }
        if (incoming.containsKey("reviewChat")) {
            List<Map<String, Object>> incomingChat = mapList(incoming.get("reviewChat"));
            review.put("conversationMemory", updateConversationMemory(review, incomingChat));
            review.put("reviewChat", normalizeReviewChat(incomingChat));
        }
    }

    private String normalizeReviewTarget(String value) {
        String target = defaultString(value, "PLANNING").trim().toUpperCase(Locale.ROOT);
        return switch (target) {
            case "STORYBOARD", "PRODUCT_FRAME", "STORYBOARD_AND_PRODUCT", "PLANNING" -> target;
            default -> "PLANNING";
        };
    }

    private List<Integer> requestedShotNumbers(
            String message,
            int selectedShotNumber,
            List<Map<String, Object>> shots
    ) {
        Set<Integer> available = shots.stream()
                .map(shot -> intValue(shot.get("shotNumber"), 0))
                .filter(value -> value > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<Integer> requested = new LinkedHashSet<>();
        if (selectedShotNumber > 0 && available.contains(selectedShotNumber)) {
            requested.add(selectedShotNumber);
        }
        String normalizedMessage = defaultString(message, "");
        String lowerMessage = normalizedMessage.toLowerCase(Locale.ROOT);
        if (lowerMessage.matches(".*\\b(all|every|each)\\s+(the\\s+)?shots?\\b.*")
                || lowerMessage.contains("complete storyboard")
                || lowerMessage.contains("entire storyboard")) {
            requested.addAll(available);
            return requested.stream().sorted().toList();
        }
        Matcher primaryScopeMatcher = PRIMARY_SHOT_SCOPE_PATTERN.matcher(normalizedMessage);
        if (primaryScopeMatcher.find()) {
            String scopeText = normalizedMessage.substring(primaryScopeMatcher.end());
            Matcher boundaryMatcher = PRIMARY_SHOT_SCOPE_BOUNDARY_PATTERN.matcher(scopeText);
            if (boundaryMatcher.find()) {
                scopeText = scopeText.substring(0, boundaryMatcher.start());
            }
            Matcher rangeMatcher = SHOT_RANGE_PATTERN.matcher(scopeText);
            while (rangeMatcher.find()) {
                int start = intValue(rangeMatcher.group(1), 0);
                int end = intValue(rangeMatcher.group(2), 0);
                if (start > 0 && end > 0 && Math.abs(end - start) <= 50) {
                    int direction = start <= end ? 1 : -1;
                    for (int value = start; value != end + direction; value += direction) {
                        if (available.contains(value)) requested.add(value);
                    }
                }
            }
            Matcher numberMatcher = NUMBER_PATTERN.matcher(scopeText);
            while (numberMatcher.find()) {
                int value = intValue(numberMatcher.group(), 0);
                if (available.contains(value)) requested.add(value);
            }
            if (!requested.isEmpty()) {
                return requested.stream().sorted().toList();
            }
        }
        Matcher referenceMatcher = SHOT_REFERENCE_PATTERN.matcher(normalizedMessage);
        while (referenceMatcher.find()) {
            int value = intValue(referenceMatcher.group(1), 0);
            if (available.contains(value)) requested.add(value);
        }
        Matcher listMatcher = SHOT_LIST_PATTERN.matcher(normalizedMessage);
        while (listMatcher.find()) {
            Matcher numberMatcher = NUMBER_PATTERN.matcher(listMatcher.group(1));
            while (numberMatcher.find()) {
                int value = intValue(numberMatcher.group(), 0);
                if (available.contains(value)) requested.add(value);
            }
        }
        return requested.stream().sorted().toList();
    }

    private List<Integer> shotNumberList(Object value, List<Map<String, Object>> shots) {
        Set<Integer> available = shots.stream()
                .map(shot -> intValue(shot.get("shotNumber"), 0))
                .filter(number -> number > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream()
                .map(item -> item instanceof Map<?, ?> map
                        ? intValue(firstNonNull(mapValue(map).get("shotNumber"), mapValue(map).get("shot_number")), 0)
                        : intValue(item, 0))
                .filter(number -> number > 0 && (available.isEmpty() || available.contains(number)))
                .distinct()
                .sorted()
                .toList();
    }

    private Map<String, Object> reviewProjectContext(
            CreatorScript script,
            Map<String, Object> payload,
            String dialogueLanguage
    ) {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("projectId", script.getProjectId() == null ? "" : script.getProjectId().toString());
        project.put("scriptId", script.getId().toString());
        project.put("title", defaultString(script.getTitle(), firstNonBlank(payload.get("projectTitle"), payload.get("title"))));
        project.put("durationSeconds", script.getDurationSeconds() == null ? 0 : script.getDurationSeconds());
        project.put("screenType", firstNonBlank(script.getScreenType(), payload.get("screenType")));
        project.put("dialogueLanguage", dialogueLanguage);
        project.put("categoryCode", defaultString(script.getCategoryCode(), firstNonBlank(payload.get("category"))));
        return project;
    }

    private List<Map<String, Object>> reviewPlanContexts(CreatorScript script) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CreatorScriptShotPlan plan : shotPlanRepository.findByScriptIdOrderByShotNumberAsc(script.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shotNumber", plan.getShotNumber());
            item.put("styleKey", plan.getStyleKey());
            item.put("status", plan.getStatus());
            item.put("storyboardTag", plan.getStoryboardTag());
            item.put("lightingBuildSheetTag", plan.getLightingBuildSheetTag());
            item.put("cameraPlanSheetTag", plan.getCameraPlanSheetTag());
            item.put("inputPayload", plan.getInputPayload());
            result.add(sanitizeMap(item));
        }
        return result;
    }

    private Map<String, Object> compactShotForAi(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> shot = sanitizeMap(source);
        List.of(
                "masterVideoPrompt",
                "videoDirectorBlueprint",
                "clientReview",
                "screenplay",
                "screenplayJson",
                "screenplayShots",
                "allShots",
                "allProductionPlans"
        ).forEach(shot::remove);
        return shot;
    }

    private Map<String, Object> compactProductionPlanForAi(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> plan = new LinkedHashMap<>();
        List.of(
                "shotNumber",
                "styleKey",
                "status",
                "storyboardTag",
                "lightingBuildSheetTag",
                "cameraPlanSheetTag"
        ).forEach(key -> {
            if (source.containsKey(key)) plan.put(key, sanitizeValue(source.get(key)));
        });
        Map<String, Object> input = mapValue(source.get("inputPayload"));
        Map<String, Object> compactInput = new LinkedHashMap<>();
        List.of(
                "shotNumber",
                "dialogueLanguage",
                "typographySystem",
                "overlayPlan",
                "videoDirectorPlan",
                "shot",
                "visualReferenceImages",
                "visualReferenceImageUrls",
                "visualReferenceUsageMode",
                "regenerationRequired",
                "storyboardRegenerationRequired",
                "productFrameRegenerationRequired"
        ).forEach(key -> {
            if (input.containsKey(key)) compactInput.put(key, sanitizeValue(input.get(key)));
        });
        if (!compactInput.isEmpty()) plan.put("inputPayload", compactInput);
        return plan;
    }

    private Map<String, Object> compactVideoDirectorPlanForAi(
            Map<String, Object> source,
            List<Integer> requestedShotNumbers,
            List<Integer> continuityOnlyShotNumbers
    ) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> compact = sanitizeMap(source);
        compact.remove("masterVideoPrompt");
        compact.remove("perSecondVideoPrompt");
        compact.remove("shotByShot");
        List<Map<String, Object>> directorShots = mapList(firstNonNull(
                source.get("shots"),
                source.get("shotByShot")
        ));
        Set<Integer> contextShotNumbers = new LinkedHashSet<>(requestedShotNumbers == null
                ? List.of()
                : requestedShotNumbers);
        if (!contextShotNumbers.isEmpty() && continuityOnlyShotNumbers != null) {
            int first = contextShotNumbers.stream().mapToInt(Integer::intValue).min().orElse(0);
            int last = contextShotNumbers.stream().mapToInt(Integer::intValue).max().orElse(0);
            if (continuityOnlyShotNumbers.contains(first - 1)) contextShotNumbers.add(first - 1);
            if (continuityOnlyShotNumbers.contains(last + 1)) contextShotNumbers.add(last + 1);
        }
        if (contextShotNumbers.isEmpty()) {
            directorShots.stream()
                    .map(shot -> intValue(shot.get("shotNumber"), 0))
                    .filter(value -> value > 0)
                    .forEach(contextShotNumbers::add);
        }
        compact.put("shots", directorShots.stream()
                .filter(shot -> contextShotNumbers.contains(intValue(shot.get("shotNumber"), 0)))
                .map(this::compactShotForAi)
                .toList());
        compact.put("includedShotNumbers", contextShotNumbers.stream().sorted().toList());
        compact.put("omittedLargeFields", List.of("masterVideoPrompt", "perSecondVideoPrompt", "duplicate shotByShot"));
        return compact;
    }

    private List<Map<String, Object>> compactReviewHistoryForAi(List<Map<String, Object>> reviewChat) {
        List<Map<String, Object>> source = reviewChat == null ? List.of() : reviewChat;
        return source.stream()
                .skip(Math.max(0, source.size() - MAX_PROVIDER_REVIEW_HISTORY_MESSAGES))
                .map(item -> {
                    Map<String, Object> message = new LinkedHashMap<>();
                    List.of(
                            "id",
                            "role",
                            "text",
                            "targetType",
                            "shotNumber",
                            "affectedShotNumbers",
                            "visualReferenceAssetIds",
                            "visualReferenceUsageMode",
                            "status",
                            "createdAt"
                    ).forEach(key -> {
                        if (item.containsKey(key)) message.put(key, sanitizeValue(item.get(key)));
                    });
                    message.computeIfPresent("text", (key, text) ->
                            trimToLength(stringValue(text), 4_000, ""));
                    return message;
                })
                .toList();
    }

    private List<Map<String, Object>> conversationMemoryForAi(
            Map<String, Object> clientReview,
            List<Map<String, Object>> reviewChat
    ) {
        List<Map<String, Object>> combined = new ArrayList<>(normalizeConversationMemory(
                clientReview == null ? null : clientReview.get("conversationMemory")
        ));
        List<Map<String, Object>> source = reviewChat == null ? List.of() : reviewChat;
        int recentStart = Math.max(0, source.size() - MAX_PROVIDER_REVIEW_HISTORY_MESSAGES);
        for (int index = 0; index < recentStart; index++) {
            addConversationMemoryItem(combined, compactConversationMemoryItem(source.get(index)));
        }
        return normalizeConversationMemory(combined);
    }

    private List<Map<String, Object>> updateConversationMemory(
            Map<String, Object> clientReview,
            List<Map<String, Object>> unboundedReviewChat
    ) {
        List<Map<String, Object>> memory = new ArrayList<>(normalizeConversationMemory(
                clientReview == null ? null : clientReview.get("conversationMemory")
        ));
        List<Map<String, Object>> source = unboundedReviewChat == null ? List.of() : unboundedReviewChat;
        int overflow = Math.max(0, source.size() - MAX_STORED_REVIEW_CHAT_MESSAGES);
        for (int index = 0; index < overflow; index++) {
            addConversationMemoryItem(memory, compactConversationMemoryItem(source.get(index)));
        }
        return normalizeConversationMemory(memory);
    }

    private void addConversationMemoryItem(
            List<Map<String, Object>> memory,
            Map<String, Object> candidate
    ) {
        String id = stringValue(candidate.get("id"));
        if (id.isBlank()) return;
        memory.removeIf(item -> id.equals(stringValue(item.get("id"))));
        memory.add(candidate);
    }

    private List<Map<String, Object>> normalizeConversationMemory(Object value) {
        List<Map<String, Object>> source = mapList(value);
        int firstIndex = Math.max(0, source.size() - MAX_STORED_REVIEW_MEMORY_ITEMS);
        List<Map<String, Object>> memory = new ArrayList<>();
        for (int index = firstIndex; index < source.size(); index++) {
            Map<String, Object> compact = compactConversationMemoryItem(source.get(index));
            if (!stringValue(compact.get("id")).isBlank()) memory.add(compact);
        }
        return memory;
    }

    private Map<String, Object> compactConversationMemoryItem(Map<String, Object> source) {
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("id", trimToLength(stringValue(source.get("id")), 120, ""));
        memory.put("role", "assistant".equalsIgnoreCase(stringValue(source.get("role")))
                ? "assistant"
                : "user");
        memory.put("text", trimToLength(
                stripEmoji(firstNonBlank(source.get("text"), source.get("message"))),
                MAX_REVIEW_MEMORY_TEXT_LENGTH,
                ""
        ));
        List.of(
                "targetType",
                "shotNumber",
                "affectedShotNumbers",
                "status",
                "createdAt",
                "completedAt",
                "languageChangePrompt",
                "sourceDialogueLanguage",
                "targetDialogueLanguage"
        ).forEach(key -> {
            if (source.containsKey(key)) memory.put(key, sanitizeValue(source.get(key)));
        });
        Map<String, Object> proposal = mapValue(source.get("proposal"));
        String decisionSummary = trimToLength(
                stringValue(proposal.get("changeSummary")),
                MAX_REVIEW_MEMORY_TEXT_LENGTH,
                ""
        );
        if (!decisionSummary.isBlank()) memory.put("decisionSummary", decisionSummary);
        if (proposal.containsKey("requiresFrameRegeneration")) {
            memory.put("requiresFrameRegeneration", proposal.get("requiresFrameRegeneration"));
        }
        return sanitizeMap(memory);
    }

    private Map<String, Object> compactClientReviewForShotPlan(
            Map<String, Object> clientReview,
            int shotNumber
    ) {
        Map<String, Object> compact = new LinkedHashMap<>();
        List.of(
                "storyboardFeedback",
                "productionFramesFeedback",
                "dialogueFeedback",
                "dialogueLanguage",
                "reviewStatus",
                "referenceUsageMode",
                "emojisAllowed",
                "detailLevel",
                "appliedReviewCount",
                "updatedAt"
        ).forEach(key -> {
            if (clientReview.containsKey(key)) compact.put(key, sanitizeValue(clientReview.get(key)));
        });
        compact.put("frameFeedback", frameFeedbackForShot(clientReview, shotNumber).stream()
                .limit(12)
                .map(this::sanitizeMap)
                .toList());
        compact.put("referenceUrls", normalizeReferenceUrls(
                stringList(clientReview.get("referenceUrls"))
        ));
        compact.put("chatHistoryStoredCentrally", true);
        return compact;
    }

    private Map<String, Object> compactClientReviewForAi(
            Map<String, Object> clientReview,
            List<Map<String, Object>> activeVisualReferenceImages,
            String selectedReviewMessageId
    ) {
        Map<String, Object> compact = new LinkedHashMap<>();
        List.of(
                "storyboardFeedback",
                "productionFramesFeedback",
                "dialogueFeedback",
                "dialogueLanguage",
                "referenceUrls",
                "referenceUsageMode",
                "fontReferenceImages",
                "typographySystem",
                "overlayPlan",
                "frameFeedback",
                "emojisAllowed",
                "detailLevel"
        ).forEach(key -> {
            if (clientReview.containsKey(key)) compact.put(key, sanitizeValue(clientReview.get(key)));
        });
        compact.put("visualReferenceImages", activeVisualReferenceImages);
        compact.put("reviewChat", compactReviewHistoryForAi(selectedActiveReviewInstructions(
                clientReview,
                selectedReviewMessageId
        )));
        return compact;
    }

    private List<Integer> activeReviewShotNumbers(
            Map<String, Object> clientReview,
            List<Map<String, Object>> shots,
            String selectedReviewMessageId
    ) {
        Set<Integer> requested = new LinkedHashSet<>();
        for (Map<String, Object> message : selectedActiveReviewInstructions(
                clientReview,
                selectedReviewMessageId
        )) {
            requested.addAll(stringList(message.get("affectedShotNumbers")).stream()
                    .map(value -> intValue(value, 0))
                    .filter(value -> value > 0)
                    .toList());
            if (message.get("affectedShotNumbers") instanceof List<?> raw) {
                raw.stream().map(value -> intValue(value, 0)).filter(value -> value > 0).forEach(requested::add);
            }
            int shotNumber = intValue(message.get("shotNumber"), 0);
            if (shotNumber > 0) requested.add(shotNumber);
        }
        Set<Integer> available = shots.stream()
                .map(shot -> intValue(shot.get("shotNumber"), 0))
                .filter(value -> value > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        requested.retainAll(available);
        if (requested.isEmpty()) requested.addAll(available);
        return requested.stream().sorted().toList();
    }

    private Map<String, Object> reviewPlanContext(CreatorScript script, int shotNumber) {
        if (shotNumber <= 0) return Map.of();
        CreatorScriptShotPlan selected = shotPlanRepository
                .findByScriptIdOrderByShotNumberAsc(script.getId())
                .stream()
                .filter(plan -> plan.getShotNumber() != null && plan.getShotNumber() == shotNumber)
                .findFirst()
                .orElse(null);
        if (selected == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shotNumber", selected.getShotNumber());
        result.put("styleKey", selected.getStyleKey());
        result.put("status", selected.getStatus());
        result.put("storyboardTag", selected.getStoryboardTag());
        result.put("lightingBuildSheetTag", selected.getLightingBuildSheetTag());
        result.put("cameraPlanSheetTag", selected.getCameraPlanSheetTag());
        result.put("inputPayload", selected.getInputPayload());
        return result;
    }

    private Map<String, Object> reviewImagesForShot(
            Map<Integer, PreviewAssets> previewAssetsByShot,
            int shotNumber,
            Map<String, Object> shot
    ) {
        PreviewAssets preview = previewAssetsByShot.getOrDefault(shotNumber, new PreviewAssets());
        Map<String, Object> images = new LinkedHashMap<>();
        images.put("storyboardImageUrl", firstNonBlank(
                preview.storyboardUrl,
                shot.get("storyboardImageUrl"),
                shot.get("storyboard_image_url"),
                shot.get("shotDesignImageUrl")
        ));
        images.put("productionImageUrl", firstNonBlank(
                preview.productionUrl,
                shot.get("productionImageUrl"),
                shot.get("production_image_url"),
                shot.get("generatedProductImageUrl"),
                shot.get("imageAnchorUrl")
        ));
        images.put("lightingImageUrl", firstNonBlank(
                preview.lightingUrl,
                shot.get("lightingImageUrl"),
                shot.get("lightImageUrl")
        ));
        images.put("cameraImageUrl", firstNonBlank(
                preview.cameraUrl,
                shot.get("cameraPlanImageUrl"),
                shot.get("dpImageUrl"),
                shot.get("cameraImageUrl")
        ));
        return images;
    }

    private List<String> reviewImageUrls(
            String targetType,
            Map<String, Object> currentImages,
            Map<String, Object> selectedShot
    ) {
        Set<String> urls = new LinkedHashSet<>();
        if ("STORYBOARD".equals(targetType) || "STORYBOARD_AND_PRODUCT".equals(targetType)) {
            addHttpUrl(urls, currentImages.get("storyboardImageUrl"));
        }
        if ("PRODUCT_FRAME".equals(targetType) || "STORYBOARD_AND_PRODUCT".equals(targetType)) {
            addHttpUrl(urls, currentImages.get("productionImageUrl"));
        }
        if ("STORYBOARD_AND_PRODUCT".equals(targetType)) {
            addHttpUrl(urls, currentImages.get("cameraImageUrl"));
            addHttpUrl(urls, currentImages.get("lightingImageUrl"));
        } else if (urls.isEmpty() && !"PLANNING".equals(targetType)) {
            addHttpUrl(urls, currentImages.get("cameraImageUrl"));
            addHttpUrl(urls, currentImages.get("lightingImageUrl"));
        }
        collectImageUrls(selectedShot, urls, 0);
        return urls.stream().limit(8).toList();
    }

    private List<Map<String, Object>> reviewImageAssets(
            String targetType,
            Map<Integer, PreviewAssets> previewAssetsByShot,
            int shotNumber
    ) {
        PreviewAssets preview = previewAssetsByShot.getOrDefault(shotNumber, new PreviewAssets());
        List<Map<String, Object>> assets = new ArrayList<>();
        if ("STORYBOARD".equals(targetType) || "STORYBOARD_AND_PRODUCT".equals(targetType)) {
            if (preview.storyboardAsset != null) {
                assets.add(referenceAssetSummary(preview.storyboardAsset, "current_storyboard_frame"));
            }
        }
        if ("PRODUCT_FRAME".equals(targetType) || "STORYBOARD_AND_PRODUCT".equals(targetType)) {
            if (preview.productionAsset != null) {
                assets.add(referenceAssetSummary(preview.productionAsset, "current_product_frame"));
            }
        }
        if ("STORYBOARD_AND_PRODUCT".equals(targetType)) {
            if (preview.cameraAsset != null) {
                assets.add(referenceAssetSummary(preview.cameraAsset, "current_camera_plan"));
            }
            if (preview.lightingAsset != null) {
                assets.add(referenceAssetSummary(preview.lightingAsset, "current_lighting_plan"));
            }
        } else if (assets.isEmpty() && !"PLANNING".equals(targetType)) {
            if (preview.cameraAsset != null) {
                assets.add(referenceAssetSummary(preview.cameraAsset, "current_camera_plan"));
            }
            if (preview.lightingAsset != null) {
                assets.add(referenceAssetSummary(preview.lightingAsset, "current_lighting_plan"));
            }
        }
        return assets.stream().limit(8).toList();
    }

    private void collectImageUrls(Object value, Set<String> urls, int depth) {
        if (value == null || depth > 4 || urls.size() >= 8) return;
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                String normalizedKey = String.valueOf(key).toLowerCase(Locale.ROOT);
                if (normalizedKey.contains("image") || normalizedKey.contains("frame") || normalizedKey.contains("url")) {
                    if (item instanceof String) addHttpUrl(urls, item);
                    else collectImageUrls(item, urls, depth + 1);
                }
            });
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectImageUrls(item, urls, depth + 1));
        }
    }

    private void addHttpUrl(Set<String> urls, Object value) {
        String url = stringValue(value);
        if (url.startsWith("https://") || url.startsWith("http://")) urls.add(url);
    }

    private Map<String, Object> overlayForShot(Map<String, Object> clientReview, int shotNumber) {
        return mapList(clientReview.get("overlayPlan")).stream()
                .filter(item -> intValue(item.get("shotNumber"), 0) == shotNumber)
                .findFirst()
                .<Map<String, Object>>map(item -> new LinkedHashMap<>(item))
                .orElse(Map.of());
    }

    private boolean hasUsableReviewAnalysis(
            Map<String, Object> aiOutput,
            List<Integer> requestedShotNumbers,
            Map<String, Object> ragContext
    ) {
        if (aiOutput == null || aiOutput.isEmpty()) return false;
        List<Integer> requested = requestedShotNumbers == null ? List.of() : requestedShotNumbers;
        if (requested.isEmpty()) {
            return !firstPlainText(
                    aiOutput.get("changeSummary"),
                    aiOutput.get("imageRevisionPrompt"),
                    aiOutput.get("proposedShot")
            ).isBlank();
        }

        Map<Integer, Map<String, Object>> currentShots = mapsByShotNumber(mapList(ragContext.get("allShots")));
        Map<Integer, Map<String, Object>> revisions = mapsByShotNumber(copyMapList(firstNonNull(
                aiOutput.get("shotRevisions"),
                aiOutput.get("affectedShots")
        )));
        if (requested.size() == 1 && revisions.isEmpty()) {
            int shotNumber = requested.get(0);
            Map<String, Object> rootRevision = new LinkedHashMap<>();
            rootRevision.put("changeSummary", aiOutput.get("changeSummary"));
            rootRevision.put("imageRevisionPrompt", aiOutput.get("imageRevisionPrompt"));
            rootRevision.put("proposedFrameDescription", aiOutput.get("proposedFrameDescription"));
            rootRevision.put("proposedShot", aiOutput.get("proposedShot"));
            return isMeaningfulReviewRevision(rootRevision, currentShots.getOrDefault(shotNumber, Map.of()));
        }
        for (int shotNumber : requested) {
            Map<String, Object> revision = revisions.get(shotNumber);
            if (revision == null || !isMeaningfulReviewRevision(
                    revision,
                    currentShots.getOrDefault(shotNumber, Map.of())
            )) return false;
        }
        return true;
    }

    private boolean isMeaningfulReviewRevision(
            Map<String, Object> revision,
            Map<String, Object> currentShot
    ) {
        Map<String, Object> proposedShot = mapValue(firstNonNull(
                revision.get("proposedShot"),
                revision.get("shot")
        ));
        String instruction = firstPlainText(
                revision.get("proposedFrameDescription"),
                revision.get("imageRevisionPrompt"),
                revision.get("changeSummary"),
                proposedShot.get("visualDirection"),
                proposedShot.get("visualAction"),
                proposedShot.get("action"),
                proposedShot.get("description")
        );
        if (instruction.isBlank()) return false;
        String comparableInstruction = comparableText(instruction);
        for (Object value : new Object[]{
                currentShot.get("visualDirection"),
                currentShot.get("visualAction"),
                currentShot.get("action"),
                currentShot.get("description"),
                currentShot.get("title")
        }) {
            String comparableCurrent = comparableText(plainText(value));
            if (!comparableCurrent.isBlank() && comparableInstruction.equals(comparableCurrent)) return false;
        }
        return true;
    }

    private Map<String, Object> groundedReviewFallback(
            String message,
            List<Integer> requestedShotNumbers,
            Map<String, Object> ragContext,
            String targetType
    ) {
        List<Integer> requested = requestedShotNumbers == null ? List.of() : requestedShotNumbers;
        Map<Integer, Map<String, Object>> currentShots = mapsByShotNumber(mapList(ragContext.get("allShots")));
        Map<Integer, String> explicitDirections = explicitShotDirections(message);
        Map<Integer, String> storyBeats = numberedStoryBeats(message);
        List<Map<String, Object>> revisions = new ArrayList<>();
        List<Map<String, Object>> orderedBeats = new ArrayList<>();
        List<Map<String, Object>> shotMappings = new ArrayList<>();
        List<String> summaries = new ArrayList<>();

        for (int shotNumber : requested) {
            Map<String, Object> currentShot = currentShots.getOrDefault(shotNumber, Map.of());
            String explicitDirection = trimToLength(explicitDirections.get(shotNumber), 1800, "");
            String storyBeat = trimToLength(storyBeats.get(shotNumber), 1400, "");
            String requestedChange = joinNonBlank(" ", storyBeat, explicitDirection);
            if (requestedChange.isBlank()) {
                requestedChange = "Revise Shot " + shotNumber
                        + " according to the supplied client story, with a distinct visual beat and exact continuity into the adjacent approved shots.";
            }
            String currentFrame = currentFrameDescription(currentShot, shotNumber);
            String proposedFrame = requestedChange;
            if (comparableText(proposedFrame).equals(comparableText(currentFrame))) {
                proposedFrame = "Replace the current Shot " + shotNumber + " frame with this client direction: " + requestedChange;
            }

            boolean storyboardChangeRequired = true;
            boolean productFrameChangeRequired = productFrameChangeRequired(explicitDirection, requestedChange, targetType);
            Map<String, Object> proposedShot = new LinkedHashMap<>(currentShot);
            proposedShot.put("shotNumber", shotNumber);
            proposedShot.put("action", proposedFrame);
            proposedShot.put("visualDirection", proposedFrame);
            proposedShot.put("clientRevisionSource", "EXPLICIT_REVIEW_INSTRUCTION");

            String cameraPlan = fallbackCameraPlan(shotNumber, proposedFrame);
            String lightingPlan = fallbackLightingPlan(shotNumber, proposedFrame);
            String directionPlan = "Direct Shot " + shotNumber + " around this exact dramatic beat: "
                    + proposedFrame + " Preserve product identity and finish on an edit-motivated state for the next approved shot.";
            String transitionPlan = "Match the incoming light, motion, material state, and screen direction; resolve Shot "
                    + shotNumber + " into the next shot without a visual reset.";
            String imageRevisionPrompt = "Shot " + shotNumber + " revision. " + proposedFrame
                    + " Preserve the approved product name, logo, packaging, claims, proportions, and campaign identity. "
                    + "Use professional premium-ad composition; reference images are inspiration only unless explicitly marked as an exact source.";

            Map<String, Object> revision = new LinkedHashMap<>();
            revision.put("shotNumber", shotNumber);
            revision.put("changeSummary", trimToLength(proposedFrame, 1200, "Revise Shot " + shotNumber));
            revision.put("currentFrameDescription", currentFrame);
            revision.put("proposedFrameDescription", proposedFrame);
            revision.put("imageRevisionPrompt", imageRevisionPrompt);
            revision.put("proposedShot", proposedShot);
            revision.put("proposedOverlayPlan", Map.of());
            revision.put("continuityIn", "Continue the last approved material, light direction, and motion state entering Shot " + shotNumber + ".");
            revision.put("continuityOut", "End Shot " + shotNumber + " on the exact visual state that motivates the following shot.");
            revision.put("cameraPlan", cameraPlan);
            revision.put("lensFocusPlan", fallbackLensFocusPlan(proposedFrame));
            revision.put("lightingPlan", lightingPlan);
            revision.put("directionPlan", directionPlan);
            revision.put("transitionPlan", transitionPlan);
            revision.put("soundPlan", "Use one restrained premium sound accent synchronized to Shot " + shotNumber + "'s reveal and outgoing edit.");
            revision.put("storyboardChangeRequired", storyboardChangeRequired);
            revision.put("productFrameChangeRequired", productFrameChangeRequired);
            revisions.add(revision);

            orderedBeats.add(Map.of(
                    "shotNumber", shotNumber,
                    "beat", trimToLength(proposedFrame, 800, "Shot " + shotNumber + " revision")
            ));
            shotMappings.add(Map.of(
                    "shotNumber", shotNumber,
                    "current", currentFrame,
                    "proposed", proposedFrame
            ));
            summaries.add("Shot " + shotNumber + ": " + trimToLength(proposedFrame, 180, "revised"));
        }

        Map<String, Object> interpretation = new LinkedHashMap<>();
        interpretation.put("premise", "Use the client's supplied story and shot notes as the authoritative revision direction.");
        interpretation.put("orderedBeats", orderedBeats);
        interpretation.put("shotMappings", shotMappings);
        interpretation.put("continuityArc", "Treat the affected shots as one continuous sequence while all other shots remain read-only continuity anchors.");

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("assistantMessage", "I inspected the current plan and rebuilt a distinct shot-by-shot draft from your explicit instructions. "
                + String.join(" ", summaries)
                + " This is a preview only. Apply it to update planning and regenerate only the marked storyboard or product frames.");
        fallback.put("changeSummary", String.join(" ", summaries));
        fallback.put("imageRevisionPrompt", requested.size() == 1 && !revisions.isEmpty()
                ? revisions.get(0).get("imageRevisionPrompt")
                : "Use each shot-specific imageRevisionPrompt; do not replace them with one generic prompt.");
        fallback.put("proposedShot", requested.size() == 1 && !revisions.isEmpty()
                ? revisions.get(0).get("proposedShot")
                : Map.of());
        fallback.put("affectedShotNumbers", requested);
        fallback.put("shotRevisions", revisions);
        fallback.put("storyInterpretation", interpretation);
        fallback.put("requiresFrameRegeneration", !requested.isEmpty());
        fallback.put("affectedPlanningStages", List.of(
                "storyline", "screenplay", "shot_plan", "storyboard", "production_frames", "video_handoff"
        ));
        fallback.put("analysisSource", "GROUNDED_FALLBACK");
        fallback.put("analysisWarning", "The AI response was incomplete, so this draft was rebuilt from the explicit shot instructions and current project context instead of showing a copied no-op preview.");
        return fallback;
    }

    private Map<Integer, String> explicitShotDirections(String message) {
        Map<Integer, String> result = new LinkedHashMap<>();
        String source = defaultString(message, "");
        Matcher structuredHeadingMatcher = Pattern.compile(
                "(?is)\\bshot\\s*#?\\s*(\\d+)\\b\\s*[:\\-\\u2013\\u2014]\\s*(.*?)(?=\\bshot\\s*#?\\s*\\d+\\b\\s*[:\\-\\u2013\\u2014]|\\z)"
        ).matcher(source);
        while (structuredHeadingMatcher.find()) {
            int shotNumber = intValue(structuredHeadingMatcher.group(1), 0);
            String direction = trimToLength(structuredHeadingMatcher.group(2), 1800, "");
            if (shotNumber > 0 && !direction.isBlank()) result.put(shotNumber, direction);
        }
        Matcher lineDirectionMatcher = Pattern.compile(
                "(?im)^\\s*(?:[-*]\\s*)?(?:(?:in|for)\\s+)?shot\\s*#?\\s*(\\d+)\\b\\s*[:\\-\\u2013\\u2014]?\\s*([^\\r\\n]+)"
        ).matcher(source);
        while (lineDirectionMatcher.find()) {
            int shotNumber = intValue(lineDirectionMatcher.group(1), 0);
            String direction = trimToLength(lineDirectionMatcher.group(2), 1800, "");
            if (shotNumber > 0 && !direction.isBlank()) result.put(shotNumber, direction);
        }
        return result;
    }

    private Map<Integer, String> numberedStoryBeats(String message) {
        Map<Integer, String> result = new LinkedHashMap<>();
        String normalized = defaultString(message, "").replace("Story Flow ", "\n");
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*(\\d+)\\.\\s*([^\\r\\n]+)(?:\\R([^\\r\\n]+))?"
        ).matcher(normalized);
        while (matcher.find()) {
            int shotNumber = intValue(matcher.group(1), 0);
            String beat = joinNonBlank(" — ", matcher.group(2), matcher.group(3));
            if (shotNumber > 0 && !beat.isBlank()) result.put(shotNumber, beat);
        }
        return result;
    }

    private boolean productFrameChangeRequired(String explicitDirection, String requestedChange, String targetType) {
        String comparable = comparableText(joinNonBlank(" ", explicitDirection, requestedChange));
        if (comparable.contains("current product frame is good")
                || comparable.contains("product frame is good")
                || comparable.contains("keep product frame")
                || comparable.contains("do not change product frame")
                || comparable.contains("dont change product frame")) return false;
        return !"STORYBOARD".equalsIgnoreCase(targetType);
    }

    private String fallbackCameraPlan(int shotNumber, String proposedFrame) {
        String comparable = comparableText(proposedFrame);
        if (comparable.contains("ingredient") || comparable.contains("falling") || comparable.contains("texture")) {
            return "ARRI Alexa 35 with premium 65mm macro glass on motion control; calibrated descending micro-dolly for Shot "
                    + shotNumber + ", 6K capture for protected 4K delivery, with a repeatable focus path across the material action.";
        }
        if (comparable.contains("hero") || comparable.contains("cta") || comparable.contains("product frame")) {
            return "Sony Venice 2 with premium 50mm cinema prime on a slow repeatable orbit for Shot " + shotNumber
                    + "; preserve pack geometry, controlled parallax, and an exact 4K hero end frame.";
        }
        return "ARRI Alexa 35 with premium 100mm macro glass on a calibrated slider for Shot " + shotNumber
                + "; use one deliberate push or rack-focus reveal, protected highlights, and 4K delivery oversampled from 6K.";
    }

    private String fallbackLensFocusPlan(String proposedFrame) {
        return "Use premium cinema glass appropriate to the requested scale, measured near/mid/far focus marks, remote follow focus, and one motivated rack that reveals the decisive detail: "
                + trimToLength(proposedFrame, 500, "the revised visual beat") + ".";
    }

    private String fallbackLightingPlan(int shotNumber, String proposedFrame) {
        return "Build Shot " + shotNumber
                + " with a motivated 3200K-4000K shaped key, black negative fill, controlled warm rim separation, flagged package reflections, subtle haze only when it adds depth, and recorded intensity/angle continuity for this beat: "
                + trimToLength(proposedFrame, 500, "the revised frame") + ".";
    }

    private String currentFrameDescription(Map<String, Object> currentShot, int shotNumber) {
        String title = firstPlainText(currentShot.get("title"), "Shot " + shotNumber);
        String detail = firstPlainText(
                currentShot.get("narrativeBeatSummary"),
                currentShot.get("action"),
                currentShot.get("visualAction"),
                currentShot.get("description"),
                currentShot.get("visualDirection")
        );
        return joinNonBlank(" — ", title, detail).isBlank()
                ? "Current approved Shot " + shotNumber
                : joinNonBlank(" — ", title, detail);
    }

    private String comparableText(String value) {
        return defaultString(value, "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private Map<String, Object> reviewProposal(
            Map<String, Object> aiOutput,
            String message,
            String targetType,
            int shotNumber,
            Map<String, Object> selectedShot,
            Map<String, Object> ragContext,
            int requestedImageCount
    ) {
        List<Map<String, Object>> allShots = mapList(ragContext.get("allShots"));
        List<Map<String, Object>> shotRevisions = copyMapList(firstNonNull(
                aiOutput.get("shotRevisions"),
                aiOutput.get("affectedShots")
        ));
        LinkedHashSet<Integer> affectedShotNumbers = new LinkedHashSet<>(
                shotNumberList(aiOutput.get("affectedShotNumbers"), allShots)
        );
        for (Map<String, Object> revision : shotRevisions) {
            int revisionShotNumber = intValue(
                    firstNonNull(revision.get("shotNumber"), revision.get("shot_number")),
                    0
            );
            if (revisionShotNumber > 0) affectedShotNumbers.add(revisionShotNumber);
        }
        List<Integer> authoritativeRequestedShotNumbers = shotNumberList(
                ragContext.get("requestedShotNumbers"),
                allShots
        );
        if (!authoritativeRequestedShotNumbers.isEmpty()) {
            affectedShotNumbers.retainAll(authoritativeRequestedShotNumbers);
            affectedShotNumbers.addAll(authoritativeRequestedShotNumbers);
        }
        if (affectedShotNumbers.isEmpty() && shotNumber > 0) {
            affectedShotNumbers.add(shotNumber);
        }
        List<Integer> normalizedAffectedShotNumbers = shotNumberList(
                affectedShotNumbers.stream().toList(),
                allShots
        );
        boolean requiresFrameRegeneration = !normalizedAffectedShotNumbers.isEmpty()
                && !Boolean.FALSE.equals(aiOutput.get("requiresFrameRegeneration"));
        String resolvedTargetType = requiresFrameRegeneration
                ? "STORYBOARD_AND_PRODUCT"
                : targetType;
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("targetType", resolvedTargetType);
        proposal.put(
                "shotNumber",
                normalizedAffectedShotNumbers.size() == 1 ? normalizedAffectedShotNumbers.get(0) : ""
        );
        proposal.put("affectedShotNumbers", normalizedAffectedShotNumbers);
        proposal.put(
                "continuityAnchorShotNumbers",
                allShots.stream()
                        .map(shot -> intValue(shot.get("shotNumber"), 0))
                        .filter(value -> value > 0 && !normalizedAffectedShotNumbers.contains(value))
                        .toList()
        );
        List<Map<String, Object>> scopedShotRevisions = shotRevisions.stream()
                .filter(revision -> normalizedAffectedShotNumbers.contains(intValue(
                        firstNonNull(revision.get("shotNumber"), revision.get("shot_number")),
                        0
                )))
                .map(this::synchronizeProductFrameRevision)
                .toList();
        proposal.put("shotRevisions", scopedShotRevisions.stream().map(this::sanitizeMap).toList());
        proposal.put("changeSummary", trimToLength(
                firstNonBlank(aiOutput.get("changeSummary"), aiOutput.get("assistantMessage"), message),
                3000,
                message
        ));
        proposal.put("imageRevisionPrompt", trimToLength(
                firstNonBlank(aiOutput.get("imageRevisionPrompt"), message),
                5000,
                message
        ));
        proposal.put("proposedShot", sanitizeMap(mapValue(firstNonNull(
                aiOutput.get("proposedShot"),
                aiOutput.get("shot"),
                selectedShot
        ))));
        proposal.put("proposedOverlayPlan", sanitizeMap(mapValue(firstNonNull(
                aiOutput.get("proposedOverlayPlan"),
                aiOutput.get("overlayPlan"),
                ragContext.get("selectedOverlayPlan")
        ))));
        proposal.put("requiresFrameRegeneration", requiresFrameRegeneration);
        proposal.put("storyInterpretation", sanitizeMap(mapValue(aiOutput.get("storyInterpretation"))));
        proposal.put("analysisSource", firstNonBlank(aiOutput.get("analysisSource"), "AI"));
        String analysisWarning = stringValue(aiOutput.get("analysisWarning"));
        if (!analysisWarning.isBlank()) proposal.put("analysisWarning", analysisWarning);
        proposal.put("creativeLearningCandidates", mapList(aiOutput.get("creativeLearningCandidates")).stream()
                .map(this::sanitizeMap)
                .limit(12)
                .toList());
        proposal.put("affectedPlanningStages", stringList(firstNonNull(
                aiOutput.get("affectedPlanningStages"),
                List.of("storyline", "screenplay", "shot_plan", "storyboard", "production_frames", "video_handoff")
        )));
        proposal.put("retrievedKeys", ragContext.get("retrievedKeys"));
        proposal.put("requestedImageCount", requestedImageCount);
        proposal.put(
                "planningChangePreview",
                planningChangePreview(
                        aiOutput,
                        scopedShotRevisions,
                        normalizedAffectedShotNumbers,
                        ragContext
                )
        );
        return sanitizeMap(proposal);
    }

    private List<Map<String, Object>> planningChangePreview(
            Map<String, Object> aiOutput,
            List<Map<String, Object>> shotRevisions,
            List<Integer> affectedShotNumbers,
            Map<String, Object> ragContext
    ) {
        Map<Integer, Map<String, Object>> currentShots = mapsByShotNumber(mapList(ragContext.get("allShots")));
        Map<Integer, Map<String, Object>> currentPlans = mapsByShotNumber(mapList(ragContext.get("allProductionPlans")));
        Map<Integer, Map<String, Object>> directorShots = mapsByShotNumber(mapList(
                mapValue(ragContext.get("videoDirectorPlan")).get("shots")
        ));
        Map<Integer, Map<String, Object>> revisions = mapsByShotNumber(shotRevisions);
        Map<Integer, Map<String, Object>> overlays = mapsByShotNumber(mapList(firstNonNull(
                mapValue(ragContext.get("videoDirectorPlan")).get("overlayPlan"),
                List.of()
        )));
        List<Map<String, Object>> preview = new ArrayList<>();
        for (int shotNumber : affectedShotNumbers) {
            Map<String, Object> currentShot = currentShots.getOrDefault(shotNumber, Map.of());
            Map<String, Object> currentPlan = currentPlans.getOrDefault(shotNumber, Map.of());
            Map<String, Object> directorShot = directorShots.getOrDefault(shotNumber, Map.of());
            Map<String, Object> revision = new LinkedHashMap<>(
                    revisions.getOrDefault(shotNumber, Map.of())
            );
            if (affectedShotNumbers.size() == 1) {
                revision.putIfAbsent("proposedShot", aiOutput.get("proposedShot"));
                revision.putIfAbsent("proposedOverlayPlan", aiOutput.get("proposedOverlayPlan"));
                revision.putIfAbsent("changeSummary", aiOutput.get("changeSummary"));
                revision.putIfAbsent("imageRevisionPrompt", aiOutput.get("imageRevisionPrompt"));
                revision.putIfAbsent("proposedFrameDescription", aiOutput.get("proposedFrameDescription"));
                revision.putIfAbsent("storyboardChangeRequired", aiOutput.get("storyboardChangeRequired"));
                revision.putIfAbsent("productFrameChangeRequired", aiOutput.get("productFrameChangeRequired"));
            }
            Map<String, Object> proposedShot = mapValue(firstNonNull(
                    revision.get("proposedShot"),
                    revision.get("shot"),
                    currentShot
            ));
            Map<String, Object> overlay = mapValue(firstNonNull(
                    revision.get("proposedOverlayPlan"),
                    revision.get("overlayPlan"),
                    overlays.get(shotNumber),
                    directorShot.get("overlayPlan")
            ));
            String currentFrame = currentFrameDescription(currentShot, shotNumber);
            String proposedFrame = firstPlainText(
                    revision.get("proposedFrameDescription"),
                    revision.get("imageRevisionPrompt"),
                    revision.get("changeSummary"),
                    proposedShot.get("visualDirection"),
                    proposedShot.get("visualAction"),
                    proposedShot.get("action"),
                    proposedShot.get("description"),
                    currentFrame
            );
            if (comparableText(proposedFrame).equals(comparableText(currentFrame))) {
                proposedFrame = firstPlainText(
                        revision.get("imageRevisionPrompt"),
                        revision.get("changeSummary"),
                        "Replace the current Shot " + shotNumber + " frame with a materially different execution of the confirmed client direction."
                );
            }
            String cameraPlan = firstPlainText(
                    revision.get("cameraPlan"),
                    revision.get("cameraDirection"),
                    joinNonBlank(" | ",
                            stringValue(directorShot.get("cameraMovement")),
                            stringValue(directorShot.get("cameraAngle")),
                            stringValue(directorShot.get("cameraPackage")),
                            stringValue(directorShot.get("captureResolution")),
                            stringValue(directorShot.get("captureSettings"))
                    ),
                    currentPlan.get("cameraPlanSheetTag"),
                    "ARRI Alexa 35 or Sony Venice 2 class cinema body on a calibrated motion-control move; 4K delivery oversampled from 6K/8K when supported."
            );
            String lensFocusPlan = firstPlainText(
                    revision.get("lensFocusPlan"),
                    joinNonBlank(" | ",
                            stringValue(directorShot.get("lens")),
                            stringValue(directorShot.get("focusBehavior"))
                    ),
                    "Premium cinema glass selected for the shot scale, measured focus marks, remote follow focus, and one motivated focus event."
            );
            String lightingPlan = firstPlainText(
                    revision.get("lightingPlan"),
                    revision.get("lighting"),
                    joinNonBlank(" | ",
                            stringValue(directorShot.get("lighting")),
                            stringValue(directorShot.get("lightingPlan"))
                    ),
                    currentPlan.get("lightingBuildSheetTag"),
                    "DP and gaffer-ready motivated key, shaped negative fill, precise rim and separation, controlled reflections, declared color temperature and contrast intent, and recorded continuity marks."
            );
            String directionPlan = firstPlainText(
                    revision.get("directionPlan"),
                    revision.get("directorNotes"),
                    joinNonBlank(" | ",
                            stringValue(directorShot.get("objective")),
                            stringValue(directorShot.get("directorNotes"))
                    ),
                    "Direct one deliberate dramatic beat with disciplined product choreography, premium reveal timing, and an exact edit-ready end state."
            );
            String transitionPlan = firstPlainText(
                    revision.get("transitionPlan"),
                    joinNonBlank(" -> ",
                            firstPlainText(revision.get("continuityIn"), directorShot.get("transitionIn")),
                            firstPlainText(revision.get("continuityOut"), directorShot.get("transitionOut"))
                    ),
                    "Enter from the prior approved end state and leave on a motivated match through light, texture, shape, reflection, or motion."
            );
            String soundPlan = firstPlainText(
                    revision.get("soundPlan"),
                    revision.get("soundDesign"),
                    directorShot.get("soundDesign"),
                    "Restrained premium sound-design accent synchronized to the visual reveal and edit."
            );
            double start = doubleValue(
                    firstNonNull(directorShot.get("startTimeSeconds"), currentShot.get("startTimeSeconds"), currentShot.get("startTime")),
                    0d
            );
            double duration = Math.max(0.25d, Math.min(15d, doubleValue(
                    firstNonNull(proposedShot.get("durationSeconds"), directorShot.get("durationSeconds"), currentShot.get("durationSeconds")),
                    4d
            )));
            double end = doubleValue(
                    firstNonNull(directorShot.get("endTimeSeconds"), proposedShot.get("endTimeSeconds"), currentShot.get("endTimeSeconds")),
                    start + duration
            );
            if (end <= start) end = start + duration;
            List<Map<String, Object>> suppliedFrames = mapList(revision.get("perSecondFrames"));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shotNumber", shotNumber);
            item.put("title", firstPlainText(
                    proposedShot.get("title"),
                    currentShot.get("title"),
                    "Shot " + shotNumber
            ));
            item.put("changeSummary", firstPlainText(
                    revision.get("changeSummary"),
                    aiOutput.get("changeSummary"),
                    "Refine Shot " + shotNumber + " while preserving approved continuity."
            ));
            item.put("currentFrameDescription", currentFrame);
            item.put("proposedFrameDescription", proposedFrame);
            item.put("cameraPlan", cameraPlan);
            item.put("lensFocusPlan", lensFocusPlan);
            item.put("lightingPlan", lightingPlan);
            item.put("directionPlan", directionPlan);
            item.put("transitionPlan", transitionPlan);
            item.put("soundPlan", soundPlan);
            boolean productFrameChangeRequired = !Boolean.FALSE.equals(
                    revision.get("productFrameChangeRequired")
            );
            boolean storyboardChangeRequired = productFrameChangeRequired
                    || !Boolean.FALSE.equals(revision.get("storyboardChangeRequired"));
            item.put("storyboardChangeRequired", storyboardChangeRequired);
            item.put("productFrameChangeRequired", productFrameChangeRequired);
            item.put("overlayPlan", overlay);
            item.put("continuityIn", firstPlainText(revision.get("continuityIn"), directorShot.get("transitionIn")));
            item.put("continuityOut", firstPlainText(revision.get("continuityOut"), directorShot.get("transitionOut")));
            item.put("imageRevisionPrompt", firstPlainText(
                    revision.get("imageRevisionPrompt"),
                    aiOutput.get("imageRevisionPrompt"),
                    proposedFrame
            ));
            item.put("perSecondFrames", previewPerSecondFrames(
                    suppliedFrames,
                    start,
                    end,
                    proposedFrame,
                    cameraPlan,
                    lightingPlan,
                    lensFocusPlan,
                    directionPlan,
                    transitionPlan
            ));
            item.put("premiumStandards", Map.of(
                    "capture", "Professional cinema capture only: ARRI Alexa 35 or Sony Venice 2 class, premium glass, controlled exposure, 10/12-bit log or RAW intent, and 4K delivery.",
                    "cameraDepartment", "DP, camera-operator, and focus-puller decisions with calibrated movement and measured focus marks; no phone, casual, rookie, or automatic execution.",
                    "lightingDepartment", "Gaffer-ready motivated lighting with shaped contrast, modifiers, flagging, reflection control, and recorded continuity.",
                    "direction", "Commercial-director intent with dramatic purpose, reveal discipline, precise product choreography, and an edit-motivated end state."
            ));
            preview.add(sanitizeMap(item));
        }
        return preview;
    }

    private Map<String, Object> synchronizeProductFrameRevision(Map<String, Object> source) {
        Map<String, Object> revision = new LinkedHashMap<>(source == null ? Map.of() : source);
        boolean productFrameChangeRequired = !Boolean.FALSE.equals(
                revision.get("productFrameChangeRequired")
        );
        boolean storyboardChangeRequired = productFrameChangeRequired
                || !Boolean.FALSE.equals(revision.get("storyboardChangeRequired"));
        revision.put("storyboardChangeRequired", storyboardChangeRequired);
        revision.put("productFrameChangeRequired", productFrameChangeRequired);
        return revision;
    }

    private List<Map<String, Object>> previewPerSecondFrames(
            List<Map<String, Object>> suppliedFrames,
            double start,
            double end,
            String proposedFrame,
            String cameraPlan,
            String lightingPlan,
            String lensFocusPlan,
            String directionPlan,
            String transitionPlan
    ) {
        List<Map<String, Object>> frames = new ArrayList<>();
        int frameCount = Math.max(1, (int) Math.ceil(end - start));
        for (int index = 0; index < frameCount; index++) {
            Map<String, Object> supplied = index < suppliedFrames.size()
                    ? suppliedFrames.get(index)
                    : Map.of();
            double frameStart = Math.min(end, start + index);
            double frameEnd = Math.min(end, frameStart + 1d);
            String phase = index == 0
                    ? "Begin the proposed beat"
                    : index == frameCount - 1
                    ? "Resolve the proposed beat into its exact edit-ready end state"
                    : "Advance the proposed beat through one controlled visual change";
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("second", index + 1);
            frame.put("startTimeSeconds", doubleValue(supplied.get("startTimeSeconds"), frameStart));
            frame.put("endTimeSeconds", doubleValue(supplied.get("endTimeSeconds"), frameEnd));
            frame.put("frameDescription", firstPlainText(
                    supplied.get("frameDescription"),
                    phase + ": " + proposedFrame
            ));
            frame.put("cameraAction", firstPlainText(supplied.get("cameraAction"), cameraPlan));
            frame.put("lightingAction", firstPlainText(supplied.get("lightingAction"), lightingPlan));
            frame.put("focusAction", firstPlainText(supplied.get("focusAction"), lensFocusPlan));
            frame.put("directorAction", firstPlainText(supplied.get("directorAction"), directionPlan));
            frame.put("transitionAction", firstPlainText(
                    supplied.get("transitionAction"),
                    index == frameCount - 1 ? transitionPlan : "Preserve the planned screen direction and motion cadence."
            ));
            frame.put("continuityAnchor", firstPlainText(
                    supplied.get("continuityAnchor"),
                    "Preserve exact product identity, geometry, light direction, screen direction, overlay safe zone, and prior-frame end state."
            ));
            frames.add(sanitizeMap(frame));
        }
        return frames;
    }

    private List<Map<String, Object>> normalizeReviewChat(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> sourceMessages = mapList(value);
        int firstStoredIndex = Math.max(0, sourceMessages.size() - MAX_STORED_REVIEW_CHAT_MESSAGES);
        for (int sourceIndex = firstStoredIndex; sourceIndex < sourceMessages.size(); sourceIndex++) {
            Map<String, Object> source = sourceMessages.get(sourceIndex);
            String text = trimToLength(
                    stripEmoji(source.get("text")),
                    MAX_CLIENT_REVIEW_STORED_MESSAGE_LENGTH,
                    ""
            );
            if (text.isBlank()) continue;
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("id", trimToLength(
                    stringValue(source.get("id")),
                    120,
                    "review-" + UUID.randomUUID()
            ));
            message.put(
                    "role",
                    "assistant".equalsIgnoreCase(stringValue(source.get("role"))) ? "assistant" : "user"
            );
            String targetType = stringValue(source.get("targetType")).toUpperCase(Locale.ROOT);
            message.put("targetType", switch (targetType) {
                case "STORYBOARD", "PRODUCT_FRAME", "STORYBOARD_AND_PRODUCT", "PLANNING" -> targetType;
                default -> "PLANNING";
            });
            int shotNumber = intValue(source.get("shotNumber"), 0);
            message.put("shotNumber", shotNumber > 0 ? shotNumber : "");
            List<Integer> affectedShotNumbers = stringList(source.get("affectedShotNumbers")).stream()
                    .map(shotValue -> intValue(shotValue, 0))
                    .filter(shotValue -> shotValue > 0)
                    .distinct()
                    .sorted()
                    .toList();
            if (affectedShotNumbers.isEmpty() && source.get("affectedShotNumbers") instanceof List<?> rawShotNumbers) {
                affectedShotNumbers = rawShotNumbers.stream()
                        .map(shotValue -> intValue(shotValue, 0))
                        .filter(shotValue -> shotValue > 0)
                        .distinct()
                        .sorted()
                        .toList();
            }
            if (affectedShotNumbers.isEmpty() && shotNumber > 0) {
                affectedShotNumbers = List.of(shotNumber);
            }
            message.put("affectedShotNumbers", affectedShotNumbers);
            List<String> visualReferenceAssetIds = stringList(source.get("visualReferenceAssetIds")).stream()
                    .map(String::trim)
                    .filter(assetId -> !assetId.isBlank())
                    .distinct()
                    .limit(MAX_VISUAL_REFERENCE_IMAGES)
                    .toList();
            message.put("visualReferenceAssetIds", visualReferenceAssetIds);
            message.put("visualReferenceUsageMode", normalizeVisualReferenceUsageMode(source.get("visualReferenceUsageMode")));
            String status = stringValue(source.get("status")).toUpperCase(Locale.ROOT);
            message.put("status", switch (status) {
                case "PENDING", "AWAITING_ANALYSIS", "AWAITING_CONFIRMATION", "APPLYING", "COMPLETED", "FAILED" -> status;
                default -> "PENDING";
            });
            message.put("text", text);
            message.put("createdAt", trimToLength(stringValue(source.get("createdAt")), 80, OffsetDateTime.now().toString()));
            String confirmationForMessageId = trimToLength(
                    stringValue(source.get("confirmationForMessageId")),
                    120,
                    ""
            );
            if (!confirmationForMessageId.isBlank()) message.put("confirmationForMessageId", confirmationForMessageId);
            if (!stringValue(source.get("completedAt")).isBlank()) {
                message.put("completedAt", trimToLength(stringValue(source.get("completedAt")), 80, ""));
            }
            if (Boolean.TRUE.equals(source.get("languageChangePrompt"))) {
                message.put("languageChangePrompt", true);
                message.put("sourceDialogueLanguage", normalizeDialogueLanguage(
                        source.get("sourceDialogueLanguage"),
                        "English"
                ));
                message.put("targetDialogueLanguage", normalizeDialogueLanguage(
                        source.get("targetDialogueLanguage"),
                        "English"
                ));
            }
            if (source.get("generatedFrames") instanceof List<?> frames) {
                message.put("generatedFrames", frames.stream().map(this::stringValue).filter(item -> !item.isBlank()).limit(4).toList());
            }
            Map<String, Object> proposal = mapValue(source.get("proposal"));
            if (!proposal.isEmpty()) {
                boolean includeDetailedFrames = sourceIndex >= Math.max(
                        firstStoredIndex,
                        sourceMessages.size() - DETAILED_REVIEW_CHAT_TAIL_MESSAGES
                );
                message.put("proposal", compactStoredReviewProposal(proposal, includeDetailedFrames));
            }
            if (source.get("retrievedKeys") instanceof List<?> keys) {
                message.put("retrievedKeys", keys.stream().map(this::stringValue).filter(item -> !item.isBlank()).limit(20).toList());
            }
            int attachedImageCount = intValue(source.get("attachedImageCount"), 0);
            if (attachedImageCount > 0) {
                message.put("attachedImageCount", attachedImageCount);
            }
            result.add(message);
        }
        return result;
    }

    private Map<String, Object> compactStoredReviewProposal(
            Map<String, Object> source,
            boolean includeDetailedFrames
    ) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        List.of(
                "targetType",
                "shotNumber",
                "affectedShotNumbers",
                "continuityAnchorShotNumbers",
                "requiresFrameRegeneration",
                "affectedPlanningStages",
                "retrievedKeys",
                "requestedImageCount",
                "analysisSource",
                "analysisWarning"
        ).forEach(key -> {
            if (source.containsKey(key)) proposal.put(key, sanitizeValue(source.get(key)));
        });
        proposal.put("changeSummary", trimToLength(stringValue(source.get("changeSummary")), 2000, ""));
        if (!includeDetailedFrames) {
            proposal.put("detailsCompacted", true);
            return sanitizeMap(proposal);
        }
        proposal.put("imageRevisionPrompt", trimToLength(stringValue(source.get("imageRevisionPrompt")), 4000, ""));
        proposal.put("proposedShot", compactStoredProposedShot(mapValue(source.get("proposedShot"))));
        proposal.put("proposedOverlayPlan", sanitizeMap(mapValue(source.get("proposedOverlayPlan"))));
        proposal.put("storyInterpretation", sanitizeMap(mapValue(source.get("storyInterpretation"))));
        proposal.put("creativeLearningCandidates", mapList(source.get("creativeLearningCandidates")).stream()
                .limit(8)
                .map(this::sanitizeMap)
                .toList());
        proposal.put("shotRevisions", mapList(source.get("shotRevisions")).stream()
                .limit(12)
                .map(this::compactStoredShotRevision)
                .toList());
        proposal.put("planningChangePreview", mapList(source.get("planningChangePreview")).stream()
                .limit(12)
                .map(item -> compactStoredPlanningPreview(item, includeDetailedFrames))
                .toList());
        return sanitizeMap(proposal);
    }

    private Map<String, Object> compactStoredShotRevision(Map<String, Object> source) {
        Map<String, Object> revision = new LinkedHashMap<>();
        List.of(
                "shotNumber",
                "storyboardChangeRequired",
                "productFrameChangeRequired"
        ).forEach(key -> {
            if (source.containsKey(key)) revision.put(key, sanitizeValue(source.get(key)));
        });
        List.of(
                "changeSummary",
                "currentFrameDescription",
                "proposedFrameDescription",
                "imageRevisionPrompt",
                "continuityIn",
                "continuityOut",
                "cameraPlan",
                "lensFocusPlan",
                "lightingPlan",
                "directionPlan",
                "transitionPlan",
                "soundPlan"
        ).forEach(key -> {
            String text = trimToLength(stringValue(source.get(key)), "imageRevisionPrompt".equals(key) ? 4000 : 1600, "");
            if (!text.isBlank()) revision.put(key, text);
        });
        revision.put("proposedShot", compactStoredProposedShot(mapValue(firstNonNull(
                source.get("proposedShot"),
                source.get("shot")
        ))));
        revision.put("proposedOverlayPlan", sanitizeMap(mapValue(firstNonNull(
                source.get("proposedOverlayPlan"),
                source.get("overlayPlan")
        ))));
        return sanitizeMap(revision);
    }

    private Map<String, Object> compactStoredProposedShot(Map<String, Object> source) {
        Map<String, Object> shot = new LinkedHashMap<>();
        List.of(
                "shotNumber",
                "sceneNumber",
                "sequenceNumber",
                "durationSeconds",
                "startTimeSeconds",
                "endTimeSeconds",
                "storyboardChangeRequired",
                "productFrameChangeRequired"
        ).forEach(key -> {
            if (source.containsKey(key)) shot.put(key, sanitizeValue(source.get(key)));
        });
        List.of(
                "title",
                "purpose",
                "action",
                "visualAction",
                "visualDirection",
                "description",
                "narrativeBeatSummary",
                "transitionIn",
                "transitionOut",
                "clientRevisionSource"
        ).forEach(key -> {
            String text = trimToLength(stringValue(source.get(key)), 1600, "");
            if (!text.isBlank()) shot.put(key, text);
        });
        Map<String, Object> dialogue = mapValue(source.get("dialogue"));
        if (!dialogue.isEmpty()) shot.put("dialogue", sanitizeMap(dialogue));
        Map<String, Object> overlay = mapValue(firstNonNull(source.get("overlayPlan"), source.get("overlay")));
        if (!overlay.isEmpty()) shot.put("overlayPlan", sanitizeMap(overlay));
        return sanitizeMap(shot);
    }

    private Map<String, Object> compactStoredPlanningPreview(
            Map<String, Object> source,
            boolean includeDetailedFrames
    ) {
        Map<String, Object> preview = new LinkedHashMap<>();
        List.of(
                "shotNumber",
                "storyboardChangeRequired",
                "productFrameChangeRequired"
        ).forEach(key -> {
            if (source.containsKey(key)) preview.put(key, sanitizeValue(source.get(key)));
        });
        List.of(
                "title",
                "changeSummary",
                "currentFrameDescription",
                "proposedFrameDescription",
                "cameraPlan",
                "lensFocusPlan",
                "lightingPlan",
                "directionPlan",
                "transitionPlan",
                "soundPlan",
                "continuityIn",
                "continuityOut",
                "imageRevisionPrompt"
        ).forEach(key -> {
            String text = trimToLength(stringValue(source.get(key)), "imageRevisionPrompt".equals(key) ? 4000 : 1600, "");
            if (!text.isBlank()) preview.put(key, text);
        });
        preview.put("overlayPlan", sanitizeMap(mapValue(source.get("overlayPlan"))));
        preview.put("premiumStandards", sanitizeMap(mapValue(source.get("premiumStandards"))));
        if (includeDetailedFrames) {
            preview.put("perSecondFrames", mapList(source.get("perSecondFrames")).stream()
                    .limit(20)
                    .map(this::compactStoredPerSecondFrame)
                    .toList());
        } else {
            preview.put("perSecondFrames", List.of());
        }
        return sanitizeMap(preview);
    }

    private Map<String, Object> compactStoredPerSecondFrame(Map<String, Object> source) {
        Map<String, Object> frame = new LinkedHashMap<>();
        List.of("second", "startTimeSeconds", "endTimeSeconds").forEach(key -> {
            if (source.containsKey(key)) frame.put(key, sanitizeValue(source.get(key)));
        });
        List.of(
                "frameDescription",
                "cameraAction",
                "lightingAction",
                "focusAction",
                "directorAction",
                "transitionAction",
                "continuityAnchor"
        ).forEach(key -> {
            String text = trimToLength(stringValue(source.get(key)), 1200, "");
            if (!text.isBlank()) frame.put(key, text);
        });
        return sanitizeMap(frame);
    }

    private List<Map<String, Object>> normalizeVisualReferenceImages(Object values, CreatorScript script) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> requested : mapList(values)) {
            if (result.size() >= MAX_VISUAL_REFERENCE_HISTORY_IMAGES) break;
            UUID assetId;
            try {
                assetId = UUID.fromString(firstNonBlank(requested.get("assetId"), requested.get("id")));
            } catch (RuntimeException ignored) {
                continue;
            }
            CreatorAsset asset = assetRepository.findById(assetId).orElse(null);
            if (!isVisualReferenceForScript(asset, script)) continue;
            Map<String, Object> summary = visualReferenceSummary(asset);
            if (result.stream().noneMatch(item -> assetId.toString().equals(stringValue(item.get("assetId"))))) {
                result.add(summary);
            }
        }
        return result;
    }

    private boolean isVisualReferenceForScript(CreatorAsset asset, CreatorScript script) {
        if (asset == null || script == null || !ASSET_TYPE_VISUAL_REFERENCE_IMAGE.equals(asset.getAssetType())) return false;
        if (!defaultString(asset.getTenantId(), "").equals(defaultString(script.getTenantId(), ""))) return false;
        if (!defaultString(asset.getUserId(), "").equals(defaultString(script.getUserId(), ""))) return false;
        Map<String, Object> metadata = asset.getMetadata() == null ? Map.of() : asset.getMetadata();
        return script.getId().toString().equals(stringValue(metadata.get("scriptId")));
    }

    private Map<String, Object> visualReferenceSummary(CreatorAsset asset) {
        Map<String, Object> result = referenceAssetSummary(asset, "visual_inspiration_only");
        Map<String, Object> metadata = asset.getMetadata() == null ? Map.of() : asset.getMetadata();
        result.put("assetType", ASSET_TYPE_VISUAL_REFERENCE_IMAGE);
        result.put("usageMode", "INSPIRATION_ONLY");
        result.put("originalFilename", firstNonBlank(metadata.get("originalFilename"), "visual-reference"));
        return result;
    }

    private Map<String, Object> referenceAssetSummary(CreatorAsset asset, String role) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (asset == null) return result;
        String signedUrl = assetUrl(asset);
        result.put("assetId", asset.getId().toString());
        result.put("id", asset.getId().toString());
        result.put("assetType", asset.getAssetType());
        result.put("referenceRole", role);
        result.put("assetRole", role);
        result.put("bucket", asset.getBucket());
        result.put("objectKey", asset.getObjectKey());
        result.put("contentType", asset.getContentType());
        result.put("sizeBytes", asset.getSizeBytes());
        result.put("publicUrl", signedUrl);
        result.put("signedUrl", signedUrl);
        result.put("assetUrl", signedUrl);
        return result;
    }

    private List<String> referenceImageUrls(List<Map<String, Object>> images, int maxImages) {
        return images.stream()
                .map(item -> firstNonBlank(item.get("signedUrl"), item.get("publicUrl"), item.get("assetUrl")))
                .filter(url -> !url.isBlank())
                .distinct()
                .limit(maxImages)
                .toList();
    }

    private String normalizeVisualReferenceUsageMode(Object value) {
        return "EXACT_SOURCE".equalsIgnoreCase(stringValue(value))
                ? "EXACT_SOURCE"
                : "INSPIRATION_ONLY";
    }

    private String ensureApplyConfirmationQuestion(
            String text,
            String targetType,
            List<Integer> affectedShotNumbers
    ) {
        String base = stripEmoji(text).trim();
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.contains("do you want") && lower.contains("apply")) return base;
        String target;
        if (affectedShotNumbers.size() == 1) {
            target = "Shot " + affectedShotNumbers.get(0)
                    + "'s storyboard and product frame, the synchronized planning sheet, and the final per-second video prompt";
        } else if (!affectedShotNumbers.isEmpty()) {
            target = "Shots " + affectedShotNumbers.stream().map(String::valueOf).collect(Collectors.joining(", "))
                    + ", their storyboard and product frames, the synchronized planning sheets, and the final per-second video prompt";
        } else {
            target = "the complete storyline, screenplay, storyboard, DP and lighting planning sheets, overlays, and final per-second video prompt";
        }
        return trimToLength(base + " Do you want me to apply this to " + target + "?", 4000, base);
    }

    private List<String> normalizeReferenceUrls(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String url = trimToLength(value, 2000, "");
                if (!url.isBlank() && !result.contains(url)) result.add(url);
            }
        }
        for (String url : FONT_REFERENCE_URLS) if (!result.contains(url)) result.add(url);
        return result;
    }

    private List<Map<String, Object>> normalizeFontReferenceImages(Object values, CreatorScript script) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> requested : mapList(values)) {
            if (result.size() >= MAX_FONT_REFERENCE_IMAGES) break;
            UUID assetId;
            try {
                assetId = UUID.fromString(firstNonBlank(requested.get("assetId"), requested.get("id")));
            } catch (RuntimeException ignored) {
                continue;
            }
            CreatorAsset asset = assetRepository.findById(assetId).orElse(null);
            if (!isFontReferenceForScript(asset, script)) continue;
            Map<String, Object> summary = fontReferenceSummary(asset);
            if (result.stream().noneMatch(item -> assetId.toString().equals(stringValue(item.get("assetId"))))) {
                result.add(summary);
            }
        }
        return result;
    }

    private boolean isFontReferenceForScript(CreatorAsset asset, CreatorScript script) {
        if (asset == null || script == null || !ASSET_TYPE_FONT_REFERENCE_IMAGE.equals(asset.getAssetType())) return false;
        if (!defaultString(asset.getTenantId(), "").equals(defaultString(script.getTenantId(), ""))) return false;
        if (!defaultString(asset.getUserId(), "").equals(defaultString(script.getUserId(), ""))) return false;
        Map<String, Object> metadata = asset.getMetadata() == null ? Map.of() : asset.getMetadata();
        return script.getId().toString().equals(stringValue(metadata.get("scriptId")));
    }

    private Map<String, Object> fontReferenceSummary(CreatorAsset asset) {
        Map<String, Object> result = new LinkedHashMap<>();
        String signedUrl;
        try {
            signedUrl = assetStorageService.signedUrl(asset.getBucket(), asset.getObjectKey(), PREVIEW_URL_TTL);
        } catch (RuntimeException ignored) {
            signedUrl = defaultString(asset.getPublicUrl(), "");
        }
        Map<String, Object> metadata = asset.getMetadata() == null ? Map.of() : asset.getMetadata();
        result.put("assetId", asset.getId().toString());
        result.put("id", asset.getId().toString());
        result.put("assetType", ASSET_TYPE_FONT_REFERENCE_IMAGE);
        result.put("referenceRole", "typography_style_reference");
        result.put("assetRole", "typography_style_reference");
        result.put("bucket", asset.getBucket());
        result.put("objectKey", asset.getObjectKey());
        result.put("contentType", asset.getContentType());
        result.put("sizeBytes", asset.getSizeBytes());
        result.put("publicUrl", signedUrl);
        result.put("signedUrl", signedUrl);
        result.put("assetUrl", signedUrl);
        result.put("originalFilename", firstNonBlank(metadata.get("originalFilename"), "font-reference"));
        return result;
    }

    private List<String> fontReferenceImageUrls(List<Map<String, Object>> images) {
        return referenceImageUrls(images, MAX_FONT_REFERENCE_IMAGES);
    }

    private List<Map<String, Object>> copyMapList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : mapList(value)) {
            result.add(new LinkedHashMap<>(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> mapValue((Map<String, Object>) item))
                .toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(key, sanitizeValue(item)));
        return result;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof String text) return stripEmoji(text);
        if (value instanceof Map<?, ?> map) return sanitizeMap(mapValue(map));
        if (value instanceof List<?> list) return list.stream().map(this::sanitizeValue).toList();
        return value;
    }

    private String stripEmoji(Object value) {
        return stringValue(value)
                .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private boolean isChocolatePouringBeat(Map<String, Object> shot) {
        String text = shot.values().stream().map(this::plainText).collect(Collectors.joining(" ")).toLowerCase(Locale.ROOT);
        return text.contains("chocolate") && (text.contains("pour") || text.contains("drizzle"));
    }

    private CreatorScript loadScript(UUID scriptId, String tenantId, String userId) {
        return scriptRepository.findByIdAndTenantIdAndUserId(
                        scriptId,
                        defaultString(tenantId, "unknown"),
                        defaultString(userId, "anonymous")
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator script was not found."));
    }

    private StoryboardClientReviewResponse toResponse(CreatorScript script) {
        Map<String, Object> payload = script.getScriptPayload() == null ? Map.of() : script.getScriptPayload();
        Map<String, Object> review = mapValue(payload.get(CLIENT_REVIEW_KEY));
        boolean hasSavedReview = !review.isEmpty();
        Map<String, Object> typography = typographySystem(mapValue(firstNonNull(
                review.get("typographySystem"),
                payload.get("typographySystem")
        )));
        List<Map<String, Object>> shots = scriptShots(script);
        List<Map<String, Object>> overlayPlan = mapList(firstNonNull(
                review.get("overlayPlan"),
                payload.get("overlayPlan")
        ));
        if (overlayPlan.isEmpty()) {
            overlayPlan = overlayPlanForShots(List.of(), List.of(), shots, typography);
        }
        Map<String, Object> videoDirectorPlan = videoDirectorPlan(
                firstNonNull(review.get("videoDirectorPlan"), payload.get("videoDirectorPlan")),
                script,
                shots,
                typography,
                overlayPlan
        );
        Map<String, Object> propagation = new LinkedHashMap<>(mapValue(firstNonNull(
                review.get("propagation"),
                payload.get("planningPropagation")
        )));
        if (!copyMapList(payload.get(CLIENT_REVIEW_SNAPSHOTS_KEY)).isEmpty()) {
            propagation.put("undoAvailable", true);
        }
        return new StoryboardClientReviewResponse(
                script.getId(),
                defaultString(stringValue(review.get("storyboardFeedback")), DEFAULT_STORYBOARD_FEEDBACK),
                defaultString(stringValue(review.get("productionFramesFeedback")), DEFAULT_PRODUCTION_FEEDBACK),
                defaultString(stringValue(review.get("dialogueFeedback")), DEFAULT_DIALOGUE_FEEDBACK),
                normalizeDialogueLanguage(review.get("dialogueLanguage"), firstNonBlank(payload.get("dialogueLanguage"), script.getDialogueLanguage())),
                hasSavedReview
                        ? normalizeStatus(stringValue(review.get("reviewStatus")))
                        : "CHANGES_REQUESTED",
                mapList(review.get("frameFeedback")).isEmpty()
                        ? normalizeFrameFeedback(List.of())
                        : mapList(review.get("frameFeedback")),
                normalizeReferenceUrls(stringList(review.get("referenceUrls"))),
                normalizeVisualReferenceImages(review.get("visualReferenceImages"), script),
                normalizeFontReferenceImages(review.get("fontReferenceImages"), script),
                normalizeReviewChat(review.get("reviewChat")),
                typography,
                overlayPlan,
                videoDirectorPlan,
                propagation,
                mapValue(firstNonNull(review.get("creativeLearning"), payload.get("creativeLearning"))),
                shots,
                offsetDateTime(review.get("updatedAt"), hasSavedReview ? script.getUpdatedAt() : null),
                hasSavedReview ? defaultString(stringValue(review.get("updatedBy")), script.getUserId()) : ""
        );
    }

    private Map<Integer, PreviewAssets> loadPreviewAssets(CreatorScript script) {
        Map<Integer, PreviewAssets> result = new LinkedHashMap<>();
        for (CreatorAsset asset : assetRepository.findShotImageAssetsForScript(script.getId(), script.getTenantId(), script.getUserId())) {
            int shotNumber = intValue(asset.getMetadata() == null ? null : asset.getMetadata().get("shotNumber"), 0);
            if (shotNumber <= 0) continue;
            PreviewAssets preview = result.computeIfAbsent(shotNumber, ignored -> new PreviewAssets());
            String kind = assetKind(asset);
            String url = assetUrl(asset);
            if ("production".equals(kind) && preview.productionUrl.isBlank()) {
                preview.productionUrl = url; preview.productionAsset = asset;
            } else if ("lighting".equals(kind) && preview.lightingUrl.isBlank()) {
                preview.lightingUrl = url; preview.lightingAsset = asset;
            } else if ("camera".equals(kind) && preview.cameraUrl.isBlank()) {
                preview.cameraUrl = url; preview.cameraAsset = asset;
            } else if ("storyboard".equals(kind) && preview.storyboardUrl.isBlank()) {
                preview.storyboardUrl = url; preview.storyboardAsset = asset;
            }
        }
        return result;
    }

    private String assetUrl(CreatorAsset asset) {
        if (asset == null || asset.getObjectKey() == null || asset.getObjectKey().isBlank()) return "";
        try {
            return assetStorageService.signedUrl(asset.getBucket(), asset.getObjectKey(), PREVIEW_URL_TTL);
        } catch (RuntimeException ignored) {
            return defaultString(asset.getPublicUrl(), "");
        }
    }

    private String assetKind(CreatorAsset asset) {
        String raw = defaultString(
                asset == null || asset.getMetadata() == null ? null : stringValue(asset.getMetadata().get("imageKind")),
                asset == null ? "" : asset.getAssetType()
        ).toLowerCase(Locale.ROOT);
        if (raw.contains("production") || raw.contains("product") || raw.contains("anchor")) return "production";
        if (raw.contains("light")) return "lighting";
        if (raw.contains("camera") || raw.contains("dp")) return "camera";
        return "storyboard";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scriptShots(CreatorScript script) {
        if (script.getShots() != null && !script.getShots().isEmpty()) return script.getShots();
        Object raw = script.getScriptPayload() == null ? null : script.getScriptPayload().get("shots");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .<Map<String, Object>>map(item -> new LinkedHashMap<>((Map<String, Object>) item))
                .toList();
    }

    private String reviewCards(StoryboardClientReviewResponse review) {
        List<String> cards = new ArrayList<>();
        addReviewCard(cards, "Storyboard direction", review.storyboardFeedback());
        addReviewCard(cards, "Production frames", review.productionFramesFeedback());
        addReviewCard(cards, "Dialogue direction", review.dialogueFeedback());
        return cards.isEmpty() ? "" : "<div class=\"reviews\">" + String.join("", cards) + "</div>";
    }

    private void addReviewCard(List<String> cards, String label, String value) {
        if (value == null || value.isBlank()) return;
        cards.add("<div class=\"review\"><span>" + html(label) + "</span><p>" + html(trimToLength(value, 420, "")) + "</p></div>");
    }

    private String previewOverlay(
            Map<String, Object> plan,
            Map<String, Object> typography,
            long sceneMs
    ) {
        if (plan == null || plan.isEmpty() || Boolean.FALSE.equals(plan.get("enabled"))) return "";
        String text = stripEmoji(plan.get("text"));
        if (text.isBlank()) return "";
        String position = stringValue(plan.get("position")).toLowerCase(Locale.ROOT);
        String vertical = position.contains("upper") || position.contains("top")
                ? "top"
                : position.contains("center") || position.contains("middle") ? "middle" : "bottom";
        String horizontal = position.contains("left")
                ? "left"
                : position.contains("right") ? "right" : "center";
        String entrance = stringValue(plan.get("entrance")).toLowerCase(Locale.ROOT);
        String motion = entrance.contains("zoom") ? "zoom" : entrance.contains("wipe") ? "wipe" : "slide";
        String fontFamily = renderableFont(
                firstNonNull(plan.get("fontFamily"), typography.get("primaryFont")),
                "Montserrat"
        );
        int fontWeight = Math.max(400, Math.min(900, intValue(plan.get("fontWeight"), 800)));
        int fontSize = Math.max(24, Math.min(96, intValue(plan.get("fontSizePx"), 58)));
        int entranceMs = Math.max(200, Math.min(1800, intValue(plan.get("entranceDurationMs"), 650)));
        int exitMs = Math.max(150, Math.min(1200, intValue(plan.get("exitDurationMs"), 450)));
        int delayMs = Math.max(0, Math.min((int) sceneMs - entranceMs, intValue(plan.get("delayMs"), 250)));
        int holdMs = Math.max(500, Math.min(6000, intValue(plan.get("holdDurationMs"), 1800)));
        long exitDelayMs = Math.min(
                Math.max(delayMs + entranceMs, sceneMs - exitMs),
                (long) delayMs + entranceMs + holdMs
        );
        String background = overlayBackgroundClass(plan.get("backgroundStyle"));
        return "<div class=\"story-overlay " + vertical + " " + horizontal + " " + motion + " " + background
                + "\" style=\"--overlay-size:" + fontSize + "px;--overlay-in:" + entranceMs
                + "ms;--overlay-delay:" + delayMs + "ms;--overlay-out:" + exitMs
                + "ms;--overlay-out-delay:" + exitDelayMs + "ms\"><span style=\"font-family:'"
                + html(fontFamily) + "',sans-serif;font-weight:" + fontWeight + "\">"
                + html(text) + "</span></div>";
    }

    private String overlayBackgroundClass(Object value) {
        String background = stringValue(value).toLowerCase(Locale.ROOT);
        if (background.contains("outline")) return "overlay-badge";
        if (background.contains("highlight")) return "overlay-highlight";
        if (background.contains("pastel") || background.contains("tag")) return "overlay-tag";
        if (background.contains("semi-transparent") || background.contains("translucent")) return "overlay-panel";
        if (background.contains("matte black")) return "overlay-black";
        return "overlay-clean";
    }

    private String spec(String label, String value) {
        if (value == null || value.isBlank()) return "";
        return "<div class=\"spec\"><span>" + html(label) + "</span><p>" + html(trimToLength(value, 300, "")) + "</p></div>";
    }

    private long sceneDurationMs(Map<String, Object> shot) {
        double seconds = doubleValue(shot.get("durationSeconds"), 4d);
        return Math.round(Math.max(2.5d, Math.min(8d, seconds)) * 1000d);
    }

    private String timestamp(Map<String, Object> shot, long sceneMs) {
        String start = stringValue(shot.get("startTime"));
        String end = stringValue(shot.get("endTime"));
        if (!start.isBlank() && !end.isBlank()) return start + "s–" + end + "s";
        return String.format(Locale.ROOT, "%.1fs", sceneMs / 1000d);
    }

    private String plainText(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text.trim();
        if (value instanceof List<?> list) return list.stream().map(this::plainText).filter(item -> !item.isBlank()).collect(Collectors.joining(" "));
        if (value instanceof Map<?, ?> map) return map.values().stream().map(this::plainText).filter(item -> !item.isBlank()).collect(Collectors.joining(" "));
        return String.valueOf(value);
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) if (value != null) return value;
        return null;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String firstPlainText(Object... values) {
        for (Object value : values) {
            String text = plainText(value);
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private String joinNonBlank(String separator, String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) if (value != null && !value.isBlank()) present.add(value);
        return String.join(separator, present);
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private OffsetDateTime offsetDateTime(Object value, OffsetDateTime fallback) {
        try {
            return value == null ? fallback : OffsetDateTime.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String normalizeStatus(String value) {
        String status = defaultString(value, "DRAFT").trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "CHANGES_REQUESTED", "READY_FOR_CLIENT", "APPROVED" -> status;
            default -> "DRAFT";
        };
    }

    private String normalizeDialogueLanguage(Object value, String fallback) {
        return trimToLength(stringValue(value), 64, defaultString(fallback, "English"));
    }

    private String appliedDialogueLanguage(CreatorScript script) {
        if (script == null) return "English";
        for (Map<String, Object> shot : scriptShots(script)) {
            String language = stringValue(shot.get("dialogueLanguage"));
            if (!language.isBlank() && hasDialogueContent(shot)) {
                return normalizeDialogueLanguage(language, script.getDialogueLanguage());
            }
        }
        Map<String, Object> payload = mapValue(script.getScriptPayload());
        String contentRuleLanguage = stringValue(mapValue(payload.get("contentRules")).get("dialogueLanguage"));
        if (!contentRuleLanguage.isBlank()) {
            return normalizeDialogueLanguage(contentRuleLanguage, script.getDialogueLanguage());
        }
        return normalizeDialogueLanguage(script.getDialogueLanguage(), "English");
    }

    private boolean sameDialogueLanguage(String left, String right) {
        return languageKey(left).equals(languageKey(right));
    }

    private String languageKey(Object value) {
        return stringValue(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
    }

    private String ensureDialogueLanguageConfirmation(
            String assistantText,
            String userMessage,
            String appliedLanguage,
            String selectedLanguage,
            boolean pending
    ) {
        String base = trimToLength(assistantText, 4_000, "");
        if (!pending) return base;
        String languageNotice;
        if (isAffirmativeLanguageChangeReply(userMessage)) {
            languageNotice = "Confirmed: you selected " + selectedLanguage
                    + ", while the currently applied dialogue is " + appliedLanguage
                    + ". Use Apply to all planning to translate and validate the dialogue, voice-over, captions, subtitles, and overlays.";
        } else {
            languageNotice = "You selected " + selectedLanguage
                    + ", while the currently applied dialogue language is " + appliedLanguage
                    + ". Do you want to translate the dialogue, voice-over, captions, subtitles, and overlays to "
                    + selectedLanguage + " when you Apply to all planning?";
        }
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.contains(selectedLanguage.toLowerCase(Locale.ROOT))
                && lower.contains(appliedLanguage.toLowerCase(Locale.ROOT))
                && (lower.contains("translate") || lower.contains("dialogue language"))) {
            return base;
        }
        return trimToLength(languageNotice + (base.isBlank() ? "" : "\n\n" + base), 4_000, languageNotice);
    }

    private boolean isAffirmativeLanguageChangeReply(String value) {
        String text = defaultString(value, "").trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()
                || text.contains("do not")
                || text.contains("don't")
                || text.contains("dont")
                || text.startsWith("no")
                || text.contains("not now")
                || text.contains("keep the current")) {
            return false;
        }
        return text.matches("^(yes|yep|yeah|sure|ok|okay|haan|han|ha|ji)(\\b.*)?$")
                || text.contains("do it")
                || text.contains("change the dialogue")
                || text.contains("translate the dialogue")
                || text.contains("apply the language");
    }

    private boolean hasDialogueContent(Map<String, Object> shot) {
        if (shot == null || shot.isEmpty()) return false;
        return DIALOGUE_LOCALIZATION_KEYS.stream()
                .anyMatch(key -> !plainText(shot.get(key)).isBlank());
    }

    private boolean hasLocalizableDialogueContent(
            String screenplay,
            List<Map<String, Object>> shots,
            List<Map<String, Object>> overlays
    ) {
        if (!plainText(screenplay).isBlank()) return true;
        if (shots != null && shots.stream().anyMatch(this::hasDialogueContent)) return true;
        return overlays != null && overlays.stream().anyMatch(overlay ->
                !Boolean.FALSE.equals(overlay.get("enabled"))
                        && !plainText(overlay.get("text")).isBlank()
        );
    }

    private boolean hasCompleteDialogueLocalization(
            String sourceScreenplay,
            List<Map<String, Object>> sourceShots,
            List<Map<String, Object>> sourceOverlays,
            Map<String, Object> aiOutput,
            String sourceLanguage,
            String targetLanguage
    ) {
        Map<String, Object> localization = mapValue(aiOutput.get("dialogueLocalization"));
        if (!booleanValue(localization.get("translationApplied"), false)
                || !sameDialogueLanguage(stringValue(localization.get("sourceLanguage")), sourceLanguage)
                || !sameDialogueLanguage(stringValue(localization.get("targetLanguage")), targetLanguage)) {
            return false;
        }

        boolean contentChanged = false;
        boolean meaningfulTranslationExpected = false;
        String sourceScreenplayText = plainText(sourceScreenplay);
        if (!sourceScreenplayText.isBlank()) {
            String translatedScreenplay = plainText(aiOutput.get("screenplay"));
            if (translatedScreenplay.isBlank()) return false;
            contentChanged |= !sameLocalizedText(sourceScreenplayText, translatedScreenplay);
            meaningfulTranslationExpected |= isMeaningfulTranslationText(sourceScreenplayText);
        }

        List<Map<String, Object>> safeSourceShots = sourceShots == null ? List.of() : sourceShots;
        Map<Integer, Map<String, Object>> translatedShots = mapsByShotNumber(mapList(aiOutput.get("shots")));
        List<Integer> translatedShotNumbers = shotNumberList(
                localization.get("translatedShotNumbers"),
                safeSourceShots
        );
        for (int index = 0; index < safeSourceShots.size(); index++) {
            Map<String, Object> sourceShot = safeSourceShots.get(index);
            if (!hasDialogueContent(sourceShot)) continue;
            int shotNumber = intValue(sourceShot.get("shotNumber"), index + 1);
            Map<String, Object> translatedShot = translatedShots.get(shotNumber);
            if (translatedShot == null
                    || !translatedShotNumbers.contains(shotNumber)
                    || !sameDialogueLanguage(stringValue(translatedShot.get("dialogueLanguage")), targetLanguage)) {
                return false;
            }
            for (String key : DIALOGUE_LOCALIZATION_KEYS) {
                String sourceText = plainText(sourceShot.get(key));
                if (sourceText.isBlank()) continue;
                String translatedText = plainText(translatedShot.get(key));
                if (translatedText.isBlank()) return false;
                contentChanged |= !sameLocalizedText(sourceText, translatedText);
                meaningfulTranslationExpected |= isMeaningfulTranslationText(sourceText);
            }
        }

        List<Map<String, Object>> safeSourceOverlays = sourceOverlays == null ? List.of() : sourceOverlays;
        Map<Integer, Map<String, Object>> translatedOverlays = mapsByShotNumber(mapList(aiOutput.get("overlayPlan")));
        for (int index = 0; index < safeSourceOverlays.size(); index++) {
            Map<String, Object> sourceOverlay = safeSourceOverlays.get(index);
            String sourceText = plainText(sourceOverlay.get("text"));
            if (Boolean.FALSE.equals(sourceOverlay.get("enabled")) || sourceText.isBlank()) continue;
            int shotNumber = intValue(sourceOverlay.get("shotNumber"), index + 1);
            String translatedText = plainText(
                    translatedOverlays.getOrDefault(shotNumber, Map.of()).get("text")
            );
            if (translatedText.isBlank()) return false;
            contentChanged |= !sameLocalizedText(sourceText, translatedText);
            meaningfulTranslationExpected |= isMeaningfulTranslationText(sourceText);
        }
        return !meaningfulTranslationExpected || contentChanged;
    }

    private boolean sameLocalizedText(String left, String right) {
        return defaultString(left, "")
                .replaceAll("\\s+", " ")
                .trim()
                .equalsIgnoreCase(defaultString(right, "").replaceAll("\\s+", " ").trim());
    }

    private boolean isMeaningfulTranslationText(String value) {
        String text = plainText(value);
        if (text.length() >= 12) return true;
        return Pattern.compile("(?U)\\p{L}+").matcher(text).results().limit(2).count() >= 2;
    }

    private String trimToLength(String value, int maxLength, String fallback) {
        String text = defaultString(value, fallback).trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Object value, int fallback) {
        try {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(stringValue(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean flag) return flag;
        String text = stringValue(value);
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        try {
            return value instanceof Number number ? number.doubleValue() : Double.parseDouble(stringValue(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String html(Object value) {
        return stringValue(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static final class PreviewAssets {
        private String storyboardUrl = "";
        private String productionUrl = "";
        private String lightingUrl = "";
        private String cameraUrl = "";
        private CreatorAsset storyboardAsset;
        private CreatorAsset productionAsset;
        private CreatorAsset lightingAsset;
        private CreatorAsset cameraAsset;
    }
}
