package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.dto.CastAssignmentView;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.ScreenplaySceneView;
import com.dalai.llama.preprod.dto.ScreenplayView;
import com.dalai.llama.preprod.dto.ScriptCharacterView;
import com.dalai.llama.preprod.dto.ScriptView;
import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.dto.ShotView;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.service.chat.ChatServiceClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What "the client locks the full creative package" actually does: flatten everything pre-
 * production-service owns for this project (script, latest screenplay, cast, every shot + its
 * generated image prompts) into text, hand each piece to chat-service's embedding index scoped to
 * this project id, open (or reuse) a chat session, and advance the project to CLIENT_LOCKED.
 * Ingestion is best-effort per artifact -- a project missing one stage (e.g. no cast assigned yet)
 * still locks with whatever exists, same "regenerating an earlier stage doesn't block later ones"
 * philosophy {@link com.dalai.llama.preprod.service.lifecycle.ProjectStateMachine} already has.
 */
@Service
public class ProjectLockService {

    private final ProjectService projectService;
    private final ScriptGenerationService scriptGenerationService;
    private final ScreenplayGenerationService screenplayGenerationService;
    private final ShotListGenerationService shotListGenerationService;
    private final ShotImageService shotImageService;
    private final ShotImageRepository shotImageRepository;
    private final ShotImageDescriptionService shotImageDescriptionService;
    private final CastAssignmentService castAssignmentService;
    private final CastProfileService castProfileService;
    private final ChatServiceClient chatServiceClient;

    public ProjectLockService(
            ProjectService projectService,
            ScriptGenerationService scriptGenerationService,
            ScreenplayGenerationService screenplayGenerationService,
            ShotListGenerationService shotListGenerationService,
            ShotImageService shotImageService,
            ShotImageRepository shotImageRepository,
            ShotImageDescriptionService shotImageDescriptionService,
            CastAssignmentService castAssignmentService,
            CastProfileService castProfileService,
            ChatServiceClient chatServiceClient
    ) {
        this.projectService = projectService;
        this.scriptGenerationService = scriptGenerationService;
        this.screenplayGenerationService = screenplayGenerationService;
        this.shotListGenerationService = shotListGenerationService;
        this.shotImageService = shotImageService;
        this.shotImageRepository = shotImageRepository;
        this.shotImageDescriptionService = shotImageDescriptionService;
        this.castAssignmentService = castAssignmentService;
        this.castProfileService = castProfileService;
        this.chatServiceClient = chatServiceClient;
    }

    /** Deliberately NOT @Transactional at this level -- ingestion is already best-effort per
     * artifact (see class javadoc), and each sub-call (script/screenplay/cast/shots, each shot's
     * MinIO download + vision-model call) manages its own short transaction. Wrapping the whole
     * method in one transaction held a single DB connection for the entire sequential run of
     * per-shot LLM calls, which starved the connection pool badly enough to fail the pod's own
     * DB health check and get it killed mid-lock under load. */
    public void lock(UUID tenantId, UUID projectId) {
        syncChatContext(tenantId, projectId);
        projectService.advanceStatus(tenantId, projectId, ProjectStatus.CLIENT_LOCKED);
    }

    /** The same ingestion {@link #lock} does, callable on its own, independent of the client
     * actually paying to lock the package. The creator's own "chat about a shot" panel (their
     * authenticated project page, no client involved) was silently useless before a client had
     * locked anything -- confirmed live: asking about a shot, the script, or characters on an
     * un-locked project got "I don't have any specific details" every time, because this class was
     * the ONLY place anything ever got embedded, and it only ran at CLIENT_LOCKED. Each ingest
     * call upserts by source (see ChatServiceClient/EmbeddedDocumentService), so calling this
     * repeatedly as the creator reopens their chat panel, and again for real at actual lock, is
     * harmless -- same "regenerating an earlier stage doesn't block later ones" idempotency the
     * rest of this class already relies on. */
    public void syncChatContext(UUID tenantId, UUID projectId) {
        ingestScript(tenantId, projectId);
        ingestScreenplay(tenantId, projectId);
        ingestCast(tenantId, projectId);
        ingestShots(tenantId, projectId);

        if (projectService.getChatSessionId(tenantId, projectId) == null) {
            UUID sessionId = chatServiceClient.createSession(tenantId, projectId);
            projectService.attachChatSession(tenantId, projectId, sessionId);
        }
    }

    private void ingestScript(UUID tenantId, UUID projectId) {
        try {
            ScriptView script = scriptGenerationService.get(tenantId, projectId);
            StringBuilder sb = new StringBuilder();
            sb.append("Script for project ").append(projectId).append(":\n").append(script.scriptText()).append("\n\n");
            if (script.pacingStyle() != null) sb.append("Pacing: ").append(script.pacingStyle()).append("\n");
            if (script.emotionalArc() != null) sb.append("Emotional arc: ").append(script.emotionalArc()).append("\n");
            if (script.hookStrategy() != null) sb.append("Hook: ").append(script.hookStrategy()).append("\n");
            chatServiceClient.ingest(tenantId, projectId.toString(), "SCRIPT", projectId, sb.toString());
        } catch (PreProductionException ex) {
            // No script yet -- nothing to ingest, not an error.
        }
    }

    private void ingestScreenplay(UUID tenantId, UUID projectId) {
        try {
            ScreenplayView screenplay = screenplayGenerationService.get(tenantId, projectId);
            String scenes = screenplay.scenes().stream()
                    .map(this::describeScene)
                    .collect(Collectors.joining("\n"));
            chatServiceClient.ingest(tenantId, projectId.toString(), "SCREENPLAY", projectId,
                    "Screenplay (version " + screenplay.version() + ") for project " + projectId + ":\n" + scenes);
        } catch (PreProductionException ex) {
            // No screenplay yet.
        }
    }

    private String describeScene(ScreenplaySceneView scene) {
        return "Scene " + scene.sceneNumber() + " (" + scene.slug() + ") at " + scene.location()
                + (scene.timeOfDay() != null ? ", " + scene.timeOfDay() : "") + ": " + scene.summary()
                + (scene.characterFocus() != null ? " [focus: " + scene.characterFocus() + "]" : "")
                + (scene.emotionalPurpose() != null ? " -- " + scene.emotionalPurpose() : "");
    }

    private void ingestCast(UUID tenantId, UUID projectId) {
        try {
            ScriptView script = scriptGenerationService.get(tenantId, projectId);
            List<CastAssignmentView> assignments = castAssignmentService.list(projectId);
            if (assignments.isEmpty()) {
                return;
            }
            Map<UUID, CastProfileView> profilesById = castProfileService.list(tenantId, projectId, null).stream()
                    .collect(Collectors.toMap(CastProfileView::id, p -> p));
            Map<UUID, ScriptCharacterView> charactersById = script.characters().stream()
                    .collect(Collectors.toMap(ScriptCharacterView::id, c -> c));

            String cast = assignments.stream()
                    .map(a -> {
                        ScriptCharacterView character = charactersById.get(a.scriptCharacterId());
                        CastProfileView profile = profilesById.get(a.castProfileId());
                        if (character == null || profile == null) {
                            return null;
                        }
                        return character.characterName() + " (" + character.characterType() + ") is cast as "
                                + profile.displayName() + (profile.description() != null ? " -- " + profile.description() : "");
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.joining("\n"));
            chatServiceClient.ingest(tenantId, projectId.toString(), "CAST", projectId, "Cast for project " + projectId + ":\n" + cast);
        } catch (PreProductionException ex) {
            // No script/cast yet.
        }
    }

    private void ingestShots(UUID tenantId, UUID projectId) {
        List<ShotView> shots = shotListGenerationService.list(tenantId, projectId);

        List<ShotImage> bestImages = shots.stream()
                .map(shot -> bestImageFor(shot.id()))
                .filter(java.util.Objects::nonNull)
                .toList();
        // Describing is the slow part (a MinIO download plus a vision-model round-trip per image)
        // -- parallelize just that across images so locking a project with many shots doesn't
        // serialize N sequential LLM calls behind each other. An image already described on a
        // prior lock is skipped for free via the cached ShotImage.description column. The actual
        // repository saves stay sequential, deliberately outside the parallel stream: firing many
        // brand-new DB connection attempts at once (one per parallel thread) was enough to trip
        // connection refusals against this cluster's single shared Postgres under load.
        List<ShotImage> toDescribe = bestImages.stream().filter(i -> i.getDescription() == null).toList();
        Map<UUID, String> newDescriptions = new java.util.concurrent.ConcurrentHashMap<>();
        toDescribe.parallelStream().forEach(image -> {
            String description = shotImageDescriptionService.describe(tenantId, image);
            if (description != null && !description.isBlank()) {
                newDescriptions.put(image.getId(), description);
            }
        });
        for (ShotImage image : toDescribe) {
            String description = newDescriptions.get(image.getId());
            if (description != null) {
                image.setDescription(description);
                shotImageRepository.save(image);
            }
        }
        Map<UUID, ShotImage> bestImageByShotId = bestImages.stream()
                .collect(Collectors.toMap(ShotImage::getShotId, i -> i));

        for (ShotView shot : shots) {
            List<ShotImageView> images = shotImageService.list(tenantId, shot.id());
            String imageSummary = images.isEmpty() ? "(no images generated yet)"
                    : images.stream().map(i -> i.kind().toString()).collect(Collectors.joining(", "));
            ShotImage best = bestImageByShotId.get(shot.id());
            String descriptionSuffix = best != null && best.getDescription() != null
                    ? " | Image (" + best.getKind() + "): " + best.getDescription() : "";
            String content = "Shot " + shot.shotRef() + " (scene shot " + shot.shotNumber() + ", " + shot.shotType() + ") at " + shot.location()
                    + ": " + shot.action() + (shot.scriptLine() != null ? " | Line: " + shot.scriptLine() : "")
                    + " | Camera: " + shot.cameraShotSize() + " " + shot.cameraAngle() + " " + shot.cameraMovement()
                    + " | Lighting: " + shot.lightingMood()
                    + " | Images generated: " + imageSummary
                    + descriptionSuffix;
            chatServiceClient.ingest(tenantId, shot.id().toString(), "SHOT", projectId, content);
        }
    }

    /** Picks the one image worth captioning per shot -- STORYBOARD when present (the creative-
     * review image), otherwise whichever kind exists. */
    private ShotImage bestImageFor(UUID shotId) {
        List<ShotImage> images = shotImageRepository.findByShotId(shotId);
        if (images.isEmpty()) {
            return null;
        }
        return images.stream()
                .filter(i -> i.getKind() == ShotImageKind.STORYBOARD)
                .findFirst()
                .orElseGet(() -> images.get(0));
    }
}
