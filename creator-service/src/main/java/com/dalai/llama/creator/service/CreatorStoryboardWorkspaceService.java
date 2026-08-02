package com.dalai.llama.creator.service;

import com.dalai.llama.creator.ai.GeminiEmbeddingService;
import com.dalai.llama.creator.domain.entity.CreatorAsset;
import com.dalai.llama.creator.domain.entity.CreatorGenerationJob;
import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorStoryboardChatMessage;
import com.dalai.llama.creator.domain.entity.CreatorStoryboardCheckpoint;
import com.dalai.llama.creator.domain.entity.CreatorStoryboardWorkspace;
import com.dalai.llama.creator.domain.entity.CreatorStoryboardWorkspaceVersion;
import com.dalai.llama.creator.dto.response.CreatorStoryboardWorkspaceResponse;
import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.dalai.llama.creator.dto.response.WorkspaceCheckpointResponse;
import com.dalai.llama.creator.dto.response.WorkspaceChatTurnResponse;
import com.dalai.llama.creator.dto.response.WorkspaceMergeResponse;
import com.dalai.llama.creator.dto.request.WorkspaceChatRequest;
import com.dalai.llama.creator.repository.CreatorAssetRepository;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardCheckpointRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardChatMessageRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardContextChunkRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardWorkspaceRepository;
import com.dalai.llama.creator.repository.CreatorStoryboardWorkspaceVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The storyboard workspace "brain": a sandboxed fork of a script's shots
 * that a client can chat with, iterate on (including image inspiration
 * uploads scoped to a single shot), checkpoint, and revert - all without
 * touching the live script - then merge back into the main flow when done.
 *
 * <p>This is fully additive alongside {@link StoryboardClientReviewService};
 * neither its endpoints, DTOs, nor persisted review payload are touched here.
 */
@Service
public class CreatorStoryboardWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(CreatorStoryboardWorkspaceService.class);

    private static final String CHUNK_SHOT = "SHOT";
    private static final String CHUNK_SHOT_IMAGE = "SHOT_IMAGE";
    private static final String CHUNK_SCRIPT_META = "SCRIPT_META";
    private static final String SCRIPT_META_REF_ID = "script";
    private static final int RETRIEVAL_TOP_K = 6;
    private static final double IMAGE_ATTACH_DISTANCE_THRESHOLD = 0.45;
    private static final int MAX_CONVERSATION_SUMMARY_CHARS = 2000;
    private static final int MAX_RECENT_TURNS = 4;
    private static final long MAX_INSPIRATION_IMAGE_BYTES = 15L * 1024L * 1024L;
    private static final String JOB_TYPE_SHOT_IMAGE_REGEN = "WORKSPACE_SHOT_IMAGE_REGEN";
    private static final Pattern SHOT_NUMBER_PATTERN = Pattern.compile("shot\\s*#?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Duration REFERENCE_DOWNLOAD_TIMEOUT = Duration.ofSeconds(12);

    private final CreatorStoryboardWorkspaceRepository workspaceRepository;
    private final CreatorStoryboardWorkspaceVersionRepository versionRepository;
    private final CreatorStoryboardCheckpointRepository checkpointRepository;
    private final CreatorStoryboardChatMessageRepository chatMessageRepository;
    private final CreatorStoryboardContextChunkRepository contextChunkRepository;
    private final CreatorScriptRepository scriptRepository;
    private final CreatorAssetRepository assetRepository;
    private final AssetStorageService assetStorageService;
    private final CreatorAiService creatorAiService;
    private final GeminiEmbeddingService embeddingService;
    private final StoryboardImageGenerationService storyboardImageGenerationService;
    private final StoryboardClientReviewService storyboardClientReviewService;
    private final GenerationJobService generationJobService;
    private final TaskExecutor taskExecutor;
    private final WebClient referenceDownloadClient;

    public CreatorStoryboardWorkspaceService(
            CreatorStoryboardWorkspaceRepository workspaceRepository,
            CreatorStoryboardWorkspaceVersionRepository versionRepository,
            CreatorStoryboardCheckpointRepository checkpointRepository,
            CreatorStoryboardChatMessageRepository chatMessageRepository,
            CreatorStoryboardContextChunkRepository contextChunkRepository,
            CreatorScriptRepository scriptRepository,
            CreatorAssetRepository assetRepository,
            AssetStorageService assetStorageService,
            CreatorAiService creatorAiService,
            GeminiEmbeddingService embeddingService,
            StoryboardImageGenerationService storyboardImageGenerationService,
            StoryboardClientReviewService storyboardClientReviewService,
            GenerationJobService generationJobService,
            @Qualifier("creatorTaskExecutor") TaskExecutor taskExecutor,
            WebClient.Builder webClientBuilder
    ) {
        this.workspaceRepository = workspaceRepository;
        this.versionRepository = versionRepository;
        this.checkpointRepository = checkpointRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.contextChunkRepository = contextChunkRepository;
        this.scriptRepository = scriptRepository;
        this.assetRepository = assetRepository;
        this.assetStorageService = assetStorageService;
        this.creatorAiService = creatorAiService;
        this.embeddingService = embeddingService;
        this.storyboardImageGenerationService = storyboardImageGenerationService;
        this.storyboardClientReviewService = storyboardClientReviewService;
        this.generationJobService = generationJobService;
        this.taskExecutor = taskExecutor;
        this.referenceDownloadClient = webClientBuilder.clone().build();
    }

    // ------------------------------------------------------------------
    // Open / fork
    // ------------------------------------------------------------------

    @Transactional
    public CreatorStoryboardWorkspaceResponse openWorkspace(UUID scriptId, String tenantId, String userId) {
        CreatorScript script = loadScript(scriptId, tenantId, userId);
        Optional<CreatorStoryboardWorkspace> existing = workspaceRepository
                .findByScriptIdAndTenantIdAndUserIdAndStatus(scriptId, safe(tenantId), safe(userId), "ACTIVE");
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        OffsetDateTime now = OffsetDateTime.now();
        CreatorStoryboardWorkspace workspace = CreatorStoryboardWorkspace.builder()
                .id(UUID.randomUUID())
                .scriptId(scriptId)
                .tenantId(safe(tenantId))
                .userId(safe(userId))
                .status("ACTIVE")
                .currentVersion(1)
                .title(defaultString(script.getTitle(), "Untitled storyboard"))
                .conversationSummary("")
                .createdAt(now)
                .updatedAt(now)
                .build();
        workspace = workspaceRepository.save(workspace);

        List<Map<String, Object>> shots = copyShots(scriptShots(script));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shots", shots);

        CreatorStoryboardWorkspaceVersion version = CreatorStoryboardWorkspaceVersion.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .version(1)
                .parentVersion(null)
                .workspacePayload(payload)
                .summary("Initial import from script")
                .createdAt(now)
                .build();
        versionRepository.save(version);

        indexScriptMeta(workspace.getId(), script);
        for (Map<String, Object> shot : shots) {
            indexShot(workspace.getId(), shot);
        }

        return toResponse(workspace);
    }

    public List<CreatorStoryboardWorkspaceResponse> listWorkspaces(UUID scriptId, String tenantId, String userId) {
        return workspaceRepository
                .findByScriptIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(scriptId, safe(tenantId), safe(userId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    @Transactional
    public WorkspaceChatTurnResponse chat(
            UUID workspaceId,
            WorkspaceChatRequest request,
            String tenantId,
            String userId
    ) {
        CreatorStoryboardWorkspace workspace = loadWorkspace(workspaceId, tenantId, userId);
        CreatorScript script = loadScript(workspace.getScriptId(), tenantId, userId);
        CreatorStoryboardWorkspaceVersion currentVersion = loadVersion(workspaceId, workspace.getCurrentVersion());

        String message = trimToLength(request.message(), 24000, "");
        if (message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A workspace chat message is required.");
        }

        List<Integer> explicitShotNumbers = extractShotNumbers(message, request.shotNumber());
        float[] queryEmbedding = embeddingService.embed(message);
        String queryVector = GeminiEmbeddingService.toPgVectorLiteral(queryEmbedding);
        List<CreatorStoryboardContextChunkRepository.ContextChunkMatch> retrieved =
                contextChunkRepository.findMostSimilar(workspaceId, queryVector, RETRIEVAL_TOP_K);

        RetrievalContext retrievalContext = buildRetrievalContext(workspaceId, retrieved, explicitShotNumbers);
        List<String> targetImageUrls = resolveTargetImageUrls(currentVersion, retrievalContext, explicitShotNumbers);

        String renderedPrompt = buildChatPrompt(
                script,
                workspace,
                retrievalContext,
                recentTurns(workspaceId),
                message
        );

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", renderedPrompt);
        if (!targetImageUrls.isEmpty()) {
            input.put("attachReferenceImages", true);
            input.put("referenceImageUrls", targetImageUrls);
        }

        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                safe(tenantId), safe(userId), script.getProjectId(), null, null
        );
        CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(
                "WORKSPACE_CHAT_TURN", input, usageContext
        );
        Map<String, Object> output = aiResponse.output() == null ? Map.of() : aiResponse.output();
        creatorAiService.publishBillingDebit("WORKSPACE_CHAT_TURN", aiResponse, usageContext);

        String assistantResponse = trimToLength(stringValue(output.get("assistantResponse")), 12000,
                "I've noted that, but didn't produce a structured reply.");
        List<Integer> affectedShots = intList(output.get("affectedShots"));
        List<Map<String, Object>> updatedShots = mapList(output.get("updatedShots"));
        Map<String, Object> intent = mapValue(output.get("intent"));

        Integer appliedVersion = null;
        if (!updatedShots.isEmpty()) {
            appliedVersion = applyChatEdits(workspace, currentVersion, updatedShots, affectedShots, assistantResponse);
        }

        updateConversationSummary(workspace, message, assistantResponse);
        workspace.setUpdatedAt(OffsetDateTime.now());
        workspaceRepository.save(workspace);

        CreatorStoryboardChatMessage turn = CreatorStoryboardChatMessage.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .role("USER")
                .message(message)
                .assistantResponse(assistantResponse)
                .intentPayload(intent)
                .operations(List.of(Map.of(
                        "type", appliedVersion == null ? "QA" : "SHOT_UPDATE",
                        "affectedShots", affectedShots
                )))
                .affectedShots(affectedShots)
                .appliedVersion(appliedVersion)
                .createdAt(OffsetDateTime.now())
                .build();
        chatMessageRepository.save(turn);

        return new WorkspaceChatTurnResponse(
                turn.getId(), assistantResponse, affectedShots, appliedVersion, workspace.getCurrentVersion()
        );
    }

    private Integer applyChatEdits(
            CreatorStoryboardWorkspace workspace,
            CreatorStoryboardWorkspaceVersion currentVersion,
            List<Map<String, Object>> updatedShots,
            List<Integer> affectedShots,
            String assistantResponse
    ) {
        List<Map<String, Object>> mergedShots = mergeShotsByNumber(
                shotsOf(currentVersion), updatedShots
        );
        Map<String, Object> newPayload = new LinkedHashMap<>(currentVersion.getWorkspacePayload());
        newPayload.put("shots", mergedShots);

        int newVersionNumber = workspace.getCurrentVersion() + 1;
        CreatorStoryboardWorkspaceVersion newVersion = CreatorStoryboardWorkspaceVersion.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .version(newVersionNumber)
                .parentVersion(workspace.getCurrentVersion())
                .workspacePayload(newPayload)
                .operations(List.of(Map.of("type", "CHAT_SHOT_UPDATE", "shotNumbers", affectedShots)))
                .dirtyShots(affectedShots)
                .summary(trimToLength(assistantResponse, 300, ""))
                .createdAt(OffsetDateTime.now())
                .build();
        versionRepository.save(newVersion);

        workspace.setCurrentVersion(newVersionNumber);

        Set<Integer> dirty = new LinkedHashSet<>(affectedShots);
        for (Map<String, Object> shot : mergedShots) {
            if (dirty.contains(intValue(shot.get("shotNumber"), -1))) {
                indexShot(workspace.getId(), shot);
            }
        }
        return newVersionNumber;
    }

    // ------------------------------------------------------------------
    // Inspiration image -> single-shot regeneration (async, generic job infra)
    // ------------------------------------------------------------------

    @Transactional
    public GenerationJobResponse uploadShotInspirationImage(
            UUID workspaceId,
            int shotNumber,
            MultipartFile file,
            String note,
            String tenantId,
            String userId
    ) {
        CreatorStoryboardWorkspace workspace = loadWorkspace(workspaceId, tenantId, userId);
        CreatorScript script = loadScript(workspace.getScriptId(), tenantId, userId);
        CreatorStoryboardWorkspaceVersion currentVersion = loadVersion(workspaceId, workspace.getCurrentVersion());
        if (findShot(shotsOf(currentVersion), shotNumber).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shot " + shotNumber + " was not found in this workspace.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an inspiration image.");
        }
        if (file.getSize() > MAX_INSPIRATION_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inspiration images must be 15 MB or smaller.");
        }
        String contentType = defaultString(file.getContentType(), "application/octet-stream").toLowerCase(Locale.ROOT);
        String extension = switch (contentType) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inspiration image must be JPG, PNG, or WebP.");
        };

        String objectKey = "storyboard-workspaces/%s/shot-%d/inspiration/%s.%s".formatted(
                workspaceId, shotNumber, UUID.randomUUID(), extension);
        AssetStorageService.StoredObject stored;
        try {
            stored = assetStorageService.uploadCreatorAsset(objectKey, file.getBytes(), contentType, Duration.ofDays(7));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the inspiration image.", ex);
        }

        Map<String, Object> assetMetadata = new LinkedHashMap<>();
        assetMetadata.put("workspaceId", workspaceId.toString());
        assetMetadata.put("shotNumber", shotNumber);
        assetMetadata.put("source", "user_upload");
        assetMetadata.put("usageMode", "INSPIRATION_ONLY");
        CreatorAsset asset = assetRepository.saveAndFlush(CreatorAsset.builder()
                .tenantId(script.getTenantId())
                .userId(script.getUserId())
                .projectId(script.getProjectId())
                .assetType("STORYBOARD_WORKSPACE_INSPIRATION_IMAGE")
                .bucket(stored.bucket())
                .objectKey(stored.objectKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .publicUrl(stored.signedUrl())
                .metadata(assetMetadata)
                .build());

        Map<String, Object> jobInput = new LinkedHashMap<>();
        jobInput.put("workspaceId", workspaceId.toString());
        jobInput.put("shotNumber", shotNumber);
        jobInput.put("inspirationAssetUrl", stored.signedUrl());
        jobInput.put("note", defaultString(note, ""));
        CreatorGenerationJob job = generationJobService.startGenerationJob(
                JOB_TYPE_SHOT_IMAGE_REGEN, safe(tenantId), safe(userId), script.getProjectId(), jobInput
        );

        taskExecutor.execute(() -> runShotImageRegenJob(
                job.getId(), workspaceId, shotNumber, stored.signedUrl(), note, tenantId, userId
        ));

        return generationJobService.toResponse(job);
    }

    private void runShotImageRegenJob(
            UUID jobId,
            UUID workspaceId,
            int shotNumber,
            String inspirationImageUrl,
            String note,
            String tenantId,
            String userId
    ) {
        try {
            CreatorStoryboardWorkspace workspace = workspaceRepository.findById(workspaceId)
                    .orElseThrow(() -> new IllegalStateException("Workspace disappeared during regeneration."));
            CreatorScript script = scriptRepository.findById(workspace.getScriptId())
                    .orElseThrow(() -> new IllegalStateException("Script disappeared during regeneration."));
            CreatorStoryboardWorkspaceVersion currentVersion = loadVersion(workspaceId, workspace.getCurrentVersion());
            List<Map<String, Object>> shots = shotsOf(currentVersion);
            Map<String, Object> targetShot = findShot(shots, shotNumber)
                    .orElseThrow(() -> new IllegalStateException("Shot " + shotNumber + " no longer exists."));
            String previousSummary = shotIndexOf(shots, shotNumber) > 0
                    ? shotText(shots.get(shotIndexOf(shots, shotNumber) - 1)) : "(this is the first shot)";
            String nextSummary = shotIndexOf(shots, shotNumber) + 1 < shots.size()
                    ? shotText(shots.get(shotIndexOf(shots, shotNumber) + 1)) : "(this is the last shot)";

            String prompt = ("""
                    Regenerate the reference frame for one shot in a vertical short-form storyboard.
                    Use the attached inspiration image only for mood, composition, lighting, and texture -
                    never copy its exact subject, logo, or text. Preserve continuity with the surrounding shots.

                    Previous shot: %s
                    This shot (to regenerate): %s
                    Next shot: %s
                    Creator direction for this change: %s
                    """).formatted(previousSummary, shotText(targetShot), nextSummary, defaultString(note, "(none)"));

            List<StoryboardImageGenerationService.ReferenceImageInput> references = new ArrayList<>();
            downloadImageBytes(inspirationImageUrl).ifPresent(references::add);
            String existingImageUrl = shotImageUrl(targetShot);
            if (existingImageUrl != null) {
                downloadImageBytes(existingImageUrl).ifPresent(references::add);
            }

            StoryboardImageGenerationService.GeneratedImage generated = references.isEmpty()
                    ? storyboardImageGenerationService.generateStoryboardImage(prompt, script.getScreenType())
                    : storyboardImageGenerationService.generateImageFromReferences(prompt, references, script.getScreenType());

            String objectKey = "storyboard-workspaces/%s/shot-%d/generated/%s.png".formatted(
                    workspaceId, shotNumber, UUID.randomUUID());
            AssetStorageService.StoredObject stored = assetStorageService.uploadCreatorAsset(
                    objectKey, generated.bytes(), defaultString(generated.contentType(), "image/png"), Duration.ofDays(7)
            );

            Map<String, Object> updatedShot = new LinkedHashMap<>(targetShot);
            updatedShot.put("assetUrl", stored.signedUrl());
            updatedShot.put("imageUrl", stored.signedUrl());

            Integer appliedVersion = applyChatEdits(
                    workspace,
                    currentVersion,
                    List.of(updatedShot),
                    List.of(shotNumber),
                    "Regenerated shot " + shotNumber + " from an inspiration image."
            );
            workspaceRepository.save(workspace);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("workspaceId", workspaceId.toString());
            output.put("shotNumber", shotNumber);
            output.put("imageUrl", stored.signedUrl());
            output.put("appliedVersion", appliedVersion);
            generationJobService.completeGenerationJob(jobId, output);
        } catch (RuntimeException ex) {
            log.error("Workspace shot image regeneration failed jobId={} workspaceId={} shotNumber={}",
                    jobId, workspaceId, shotNumber, ex);
            generationJobService.failGenerationJob(jobId, defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
        }
    }

    // ------------------------------------------------------------------
    // Checkpoint / revert
    // ------------------------------------------------------------------

    @Transactional
    public WorkspaceCheckpointResponse createCheckpoint(
            UUID workspaceId, String title, String tenantId, String userId
    ) {
        CreatorStoryboardWorkspace workspace = loadWorkspace(workspaceId, tenantId, userId);
        CreatorStoryboardCheckpoint checkpoint = CreatorStoryboardCheckpoint.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .version(workspace.getCurrentVersion())
                .title(trimToLength(title, 255, "Checkpoint"))
                .createdBy(safe(userId))
                .createdAt(OffsetDateTime.now())
                .build();
        checkpoint = checkpointRepository.save(checkpoint);
        workspace.setActiveCheckpointId(checkpoint.getId());
        workspace.setUpdatedAt(OffsetDateTime.now());
        workspaceRepository.save(workspace);
        return new WorkspaceCheckpointResponse(
                checkpoint.getId(), checkpoint.getVersion(), checkpoint.getTitle(), checkpoint.getCreatedAt()
        );
    }

    public List<WorkspaceCheckpointResponse> listCheckpoints(UUID workspaceId, String tenantId, String userId) {
        loadWorkspace(workspaceId, tenantId, userId);
        return checkpointRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
                .map(cp -> new WorkspaceCheckpointResponse(cp.getId(), cp.getVersion(), cp.getTitle(), cp.getCreatedAt()))
                .toList();
    }

    @Transactional
    public CreatorStoryboardWorkspaceResponse revert(
            UUID workspaceId, int toVersion, String tenantId, String userId
    ) {
        CreatorStoryboardWorkspace workspace = loadWorkspace(workspaceId, tenantId, userId);
        CreatorStoryboardWorkspaceVersion target = loadVersion(workspaceId, toVersion);

        int newVersionNumber = workspace.getCurrentVersion() + 1;
        CreatorStoryboardWorkspaceVersion reverted = CreatorStoryboardWorkspaceVersion.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .version(newVersionNumber)
                .parentVersion(workspace.getCurrentVersion())
                .workspacePayload(new LinkedHashMap<>(target.getWorkspacePayload()))
                .operations(List.of(Map.of("type", "REVERT", "toVersion", toVersion)))
                .dirtyShots(List.of())
                .summary("Reverted to version " + toVersion)
                .createdAt(OffsetDateTime.now())
                .build();
        versionRepository.save(reverted);
        workspace.setCurrentVersion(newVersionNumber);
        workspace.setUpdatedAt(OffsetDateTime.now());
        workspaceRepository.save(workspace);

        for (Map<String, Object> shot : shotsOf(reverted)) {
            indexShot(workspaceId, shot);
        }
        return toResponse(workspace);
    }

    // ------------------------------------------------------------------
    // Merge back into the live script
    // ------------------------------------------------------------------

    @Transactional
    public WorkspaceMergeResponse merge(UUID workspaceId, String tenantId, String userId) {
        CreatorStoryboardWorkspace workspace = loadWorkspace(workspaceId, tenantId, userId);
        CreatorScript script = loadScript(workspace.getScriptId(), tenantId, userId);
        CreatorStoryboardWorkspaceVersion currentVersion = loadVersion(workspaceId, workspace.getCurrentVersion());
        List<Map<String, Object>> shots = shotsOf(currentVersion);

        Map<String, Object> payload = new LinkedHashMap<>(
                script.getScriptPayload() == null ? Map.of() : script.getScriptPayload()
        );
        payload.put("shots", shots);
        script.setShots(shots);
        script.setScriptPayload(payload);
        scriptRepository.save(script);
        storyboardClientReviewService.updateShotRows(script, shots);

        workspace.setStatus("MERGED");
        OffsetDateTime now = OffsetDateTime.now();
        workspace.setUpdatedAt(now);
        workspaceRepository.save(workspace);

        return new WorkspaceMergeResponse(script.getId(), workspaceId, currentVersion.getVersion(), now);
    }

    // ------------------------------------------------------------------
    // RAG indexing
    // ------------------------------------------------------------------

    private void indexScriptMeta(UUID workspaceId, CreatorScript script) {
        String content = ("Script: %s. Duration: %ss. Category: %s. Dialogue language: %s.").formatted(
                defaultString(script.getTitle(), ""),
                script.getDurationSeconds() == null ? "unknown" : script.getDurationSeconds().toString(),
                defaultString(script.getCategoryCode(), "unspecified"),
                defaultString(script.getDialogueLanguage(), "English")
        );
        upsertChunk(workspaceId, CHUNK_SCRIPT_META, SCRIPT_META_REF_ID, content);
    }

    private void indexShot(UUID workspaceId, Map<String, Object> shot) {
        int shotNumber = intValue(shot.get("shotNumber"), 0);
        if (shotNumber <= 0) return;
        upsertChunk(workspaceId, CHUNK_SHOT, String.valueOf(shotNumber), shotText(shot));

        String imageUrl = shotImageUrl(shot);
        if (imageUrl == null) {
            contextChunkRepository.deleteByWorkspaceIdAndChunkTypeAndRefId(workspaceId, CHUNK_SHOT_IMAGE, String.valueOf(shotNumber));
            return;
        }
        try {
            String caption = captionShotImage(imageUrl, shotText(shot));
            upsertChunk(workspaceId, CHUNK_SHOT_IMAGE, String.valueOf(shotNumber), caption);
        } catch (RuntimeException ex) {
            log.warn("Could not caption shot image for workspace={} shot={}: {}", workspaceId, shotNumber, ex.getMessage());
        }
    }

    private void upsertChunk(UUID workspaceId, String chunkType, String refId, String content) {
        String text = trimToLength(content, 6000, "");
        if (text.isBlank()) return;
        float[] embedding = embeddingService.embed(text);
        contextChunkRepository.upsertChunk(
                UUID.randomUUID(), workspaceId, chunkType, refId, text, text.length() / 4,
                GeminiEmbeddingService.toPgVectorLiteral(embedding)
        );
    }

    private String captionShotImage(String imageUrl, String shotContext) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", ("""
                Describe this storyboard reference frame in under 80 words for a searchable index:
                subject, composition, camera framing, lighting, color palette, and anything relevant to
                visual continuity with neighboring shots. Shot direction for context: %s
                Return JSON: {"caption": "..."}
                """).formatted(trimToLength(shotContext, 500, "")));
        input.put("attachReferenceImages", true);
        input.put("referenceImageUrls", List.of(imageUrl));
        Map<String, Object> output = creatorAiService.generate("WORKSPACE_SHOT_IMAGE_CAPTION", input);
        String caption = stringValue(output.get("caption"));
        return caption.isBlank() ? stringValue(output.get("rawText")) : caption;
    }

    // ------------------------------------------------------------------
    // Retrieval context assembly
    // ------------------------------------------------------------------

    private record RetrievalContext(String contextText, List<Integer> topShotNumbers, double topDistance) {
    }

    private RetrievalContext buildRetrievalContext(
            UUID workspaceId,
            List<CreatorStoryboardContextChunkRepository.ContextChunkMatch> retrieved,
            List<Integer> explicitShotNumbers
    ) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        List<Integer> topShotNumbers = new ArrayList<>();
        double topDistance = 1.0;
        boolean first = true;
        for (CreatorStoryboardContextChunkRepository.ContextChunkMatch match : retrieved) {
            String key = match.getChunkType() + ":" + match.getRefId();
            if (!seen.add(key)) continue;
            lines.add("[%s %s] %s".formatted(match.getChunkType(), defaultString(match.getRefId(), ""), match.getContent()));
            if (first) {
                topDistance = match.getDistance();
                first = false;
            }
            if (CHUNK_SHOT.equals(match.getChunkType()) || CHUNK_SHOT_IMAGE.equals(match.getChunkType())) {
                Integer number = parseIntOrNull(match.getRefId());
                if (number != null) topShotNumbers.add(number);
            }
        }

        // Explicit shot references (e.g. "shot 4") always get pulled in, even if the
        // similarity search didn't surface them as a top-k match.
        if (!explicitShotNumbers.isEmpty()) {
            for (String chunkType : List.of(CHUNK_SHOT, CHUNK_SHOT_IMAGE)) {
                for (var chunk : contextChunkRepository.findByWorkspaceIdAndChunkType(workspaceId, chunkType)) {
                    Integer number = parseIntOrNull(chunk.getRefId());
                    if (number == null || !explicitShotNumbers.contains(number)) continue;
                    String key = chunkType + ":" + chunk.getRefId();
                    if (!seen.add(key)) continue;
                    lines.add("[%s %s] %s".formatted(chunkType, chunk.getRefId(), chunk.getContent()));
                }
            }
            topShotNumbers.addAll(0, explicitShotNumbers);
        }
        return new RetrievalContext(String.join("\n", lines), topShotNumbers, topDistance);
    }

    private List<String> resolveTargetImageUrls(
            CreatorStoryboardWorkspaceVersion currentVersion,
            RetrievalContext retrievalContext,
            List<Integer> explicitShotNumbers
    ) {
        LinkedHashSet<Integer> targets = new LinkedHashSet<>(explicitShotNumbers);
        if (targets.isEmpty() && retrievalContext.topDistance() <= IMAGE_ATTACH_DISTANCE_THRESHOLD) {
            targets.addAll(retrievalContext.topShotNumbers().stream().limit(1).toList());
        }
        List<Map<String, Object>> shots = shotsOf(currentVersion);
        List<String> urls = new ArrayList<>();
        for (Integer shotNumber : targets) {
            findShot(shots, shotNumber).map(this::shotImageUrl).filter(u -> u != null).ifPresent(urls::add);
            if (urls.size() >= 3) break;
        }
        return urls;
    }

    private List<CreatorStoryboardChatMessage> recentTurns(UUID workspaceId) {
        List<CreatorStoryboardChatMessage> all = chatMessageRepository.findByWorkspaceIdOrderByCreatedAtAsc(workspaceId);
        int from = Math.max(0, all.size() - MAX_RECENT_TURNS);
        return all.subList(from, all.size());
    }

    private String buildChatPrompt(
            CreatorScript script,
            CreatorStoryboardWorkspace workspace,
            RetrievalContext retrievalContext,
            List<CreatorStoryboardChatMessage> recentTurns,
            String message
    ) {
        StringBuilder recent = new StringBuilder();
        for (CreatorStoryboardChatMessage turn : recentTurns) {
            recent.append("User: ").append(trimToLength(turn.getMessage(), 400, "")).append('\n');
            recent.append("Assistant: ").append(trimToLength(turn.getAssistantResponse(), 400, "")).append('\n');
        }
        return ("""
                You are the creative "brain" for a short-form video storyboard workspace. You know this
                project's script, its shots, and the reference images already generated for it. The client
                is discussing changes in a sandboxed workspace - nothing here affects the live project until
                they explicitly merge it back.

                Conversation summary so far: %s

                Relevant project context (retrieved for this message):
                %s

                Recent turns:
                %s

                Client's new message: %s

                Decide what, if anything, should change. If the client is only asking a question, leave
                updatedShots empty. If they want a change, return the FULL updated object for every shot you
                changed (not a diff) in updatedShots, preserving all fields you did not intentionally change,
                and never break continuity with neighboring shots.

                Return strict JSON:
                {"assistantResponse": "reply to the client", "intent": {"type": "...", "targetShots": [...]},
                 "affectedShots": [shot numbers actually changed], "updatedShots": [full shot objects, only for changed shots]}
                """).formatted(
                defaultString(workspace.getConversationSummary(), "(none yet)"),
                retrievalContext.contextText().isBlank() ? "(no directly relevant context found)" : retrievalContext.contextText(),
                recent.isEmpty() ? "(no prior turns)" : recent.toString(),
                message
        );
    }

    private void updateConversationSummary(CreatorStoryboardWorkspace workspace, String message, String assistantResponse) {
        String line = "User: %s -> AI: %s".formatted(trimToLength(message, 120, ""), trimToLength(assistantResponse, 150, ""));
        String combined = defaultString(workspace.getConversationSummary(), "").isBlank()
                ? line
                : workspace.getConversationSummary() + "\n" + line;
        if (combined.length() > MAX_CONVERSATION_SUMMARY_CHARS) {
            combined = combined.substring(combined.length() - MAX_CONVERSATION_SUMMARY_CHARS);
        }
        workspace.setConversationSummary(combined);
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private CreatorScript loadScript(UUID scriptId, String tenantId, String userId) {
        return scriptRepository.findByIdAndTenantIdAndUserId(scriptId, safe(tenantId), safe(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator script was not found."));
    }

    private CreatorStoryboardWorkspace loadWorkspace(UUID workspaceId, String tenantId, String userId) {
        return workspaceRepository.findByIdAndTenantIdAndUserId(workspaceId, safe(tenantId), safe(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Storyboard workspace was not found."));
    }

    private CreatorStoryboardWorkspaceVersion loadVersion(UUID workspaceId, int version) {
        return versionRepository.findByWorkspaceIdAndVersion(workspaceId, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace version " + version + " was not found."));
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> shotsOf(CreatorStoryboardWorkspaceVersion version) {
        Object raw = version.getWorkspacePayload() == null ? null : version.getWorkspacePayload().get("shots");
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .<Map<String, Object>>map(item -> new LinkedHashMap<>((Map<String, Object>) item))
                .toList();
    }

    private List<Map<String, Object>> copyShots(List<Map<String, Object>> shots) {
        return shots.stream().map(LinkedHashMap::new).collect(Collectors.toList());
    }

    private List<Map<String, Object>> mergeShotsByNumber(
            List<Map<String, Object>> existing, List<Map<String, Object>> updates
    ) {
        Map<Integer, Map<String, Object>> byNumber = new LinkedHashMap<>();
        for (Map<String, Object> shot : existing) {
            byNumber.put(intValue(shot.get("shotNumber"), -1), new LinkedHashMap<>(shot));
        }
        for (Map<String, Object> update : updates) {
            int shotNumber = intValue(update.get("shotNumber"), -1);
            if (shotNumber < 0) continue;
            byNumber.put(shotNumber, new LinkedHashMap<>(update));
        }
        return new ArrayList<>(byNumber.values());
    }

    private Optional<Map<String, Object>> findShot(List<Map<String, Object>> shots, int shotNumber) {
        return shots.stream().filter(s -> intValue(s.get("shotNumber"), -1) == shotNumber).findFirst();
    }

    private int shotIndexOf(List<Map<String, Object>> shots, int shotNumber) {
        for (int i = 0; i < shots.size(); i++) {
            if (intValue(shots.get(i).get("shotNumber"), -1) == shotNumber) return i;
        }
        return -1;
    }

    private String shotText(Map<String, Object> shot) {
        StringBuilder builder = new StringBuilder();
        builder.append("Shot ").append(intValue(shot.get("shotNumber"), 0)).append(": ");
        appendField(builder, "Title", shot.get("title"));
        appendField(builder, "Action", shot.get("action"));
        appendField(builder, "Dialogue", shot.get("dialogue"));
        appendField(builder, "Voice-over", shot.get("voiceOver"));
        appendField(builder, "Camera angle", shot.get("cameraAngle"));
        appendField(builder, "Camera movement", shot.get("cameraMovement"));
        appendField(builder, "Lighting", shot.get("lighting"));
        appendField(builder, "Environment", shot.get("environment"));
        return trimToLength(builder.toString(), 1500, "");
    }

    private void appendField(StringBuilder builder, String label, Object value) {
        String text = stringValue(value);
        if (text.isBlank()) return;
        builder.append(label).append(": ").append(trimToLength(text, 300, "")).append(". ");
    }

    private String shotImageUrl(Map<String, Object> shot) {
        for (String key : List.of("assetUrl", "signedUrl", "publicUrl", "imageUrl", "url")) {
            String value = stringValue(shot.get(key));
            if (!value.isBlank()) return value;
        }
        Object imagesRaw = shot.get("images");
        if (imagesRaw instanceof Map<?, ?> images) {
            for (String key : List.of("assetUrl", "signedUrl", "publicUrl", "imageUrl", "url")) {
                String value = stringValue(images.get(key));
                if (!value.isBlank()) return value;
            }
        }
        return null;
    }

    private Optional<StoryboardImageGenerationService.ReferenceImageInput> downloadImageBytes(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            org.springframework.http.ResponseEntity<byte[]> response = referenceDownloadClient.get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .toEntity(byte[].class)
                    .block(REFERENCE_DOWNLOAD_TIMEOUT);
            byte[] bytes = response == null ? null : response.getBody();
            if (bytes == null || bytes.length == 0) return Optional.empty();
            MediaType contentType = response.getHeaders().getContentType();
            String contentTypeValue = contentType != null ? contentType.toString() : MediaType.IMAGE_JPEG_VALUE;
            return Optional.of(new StoryboardImageGenerationService.ReferenceImageInput(bytes, contentTypeValue, "inspiration"));
        } catch (RuntimeException ex) {
            log.warn("Could not download reference image from {}: {}", url, ex.getMessage());
            return Optional.empty();
        }
    }

    private List<Integer> extractShotNumbers(String message, Integer explicitShotNumber) {
        LinkedHashSet<Integer> numbers = new LinkedHashSet<>();
        if (explicitShotNumber != null && explicitShotNumber > 0) numbers.add(explicitShotNumber);
        Matcher matcher = SHOT_NUMBER_PATTERN.matcher(message);
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // not a valid shot number, skip
            }
        }
        return new ArrayList<>(numbers);
    }

    private Integer parseIntOrNull(String value) {
        try {
            return value == null ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private CreatorStoryboardWorkspaceResponse toResponse(CreatorStoryboardWorkspace workspace) {
        return new CreatorStoryboardWorkspaceResponse(
                workspace.getId(),
                workspace.getScriptId(),
                workspace.getStatus(),
                workspace.getCurrentVersion(),
                workspace.getTitle(),
                workspace.getConversationSummary(),
                workspace.getCreatedAt(),
                workspace.getUpdatedAt()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .<Map<String, Object>>map(item -> new LinkedHashMap<>((Map<String, Object>) item))
                .toList();
    }

    private List<Integer> intList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number) result.add(number.intValue());
            else if (item != null) {
                try {
                    result.add(Integer.parseInt(item.toString().trim()));
                } catch (NumberFormatException ignored) {
                    // skip non-numeric entries
                }
            }
        }
        return result;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String trimToLength(String value, int maxLength, String fallback) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) return fallback;
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
