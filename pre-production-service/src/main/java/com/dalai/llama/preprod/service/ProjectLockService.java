package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.dto.CastAssignmentView;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.ScreenplaySceneView;
import com.dalai.llama.preprod.dto.ScreenplayView;
import com.dalai.llama.preprod.dto.ScriptCharacterView;
import com.dalai.llama.preprod.dto.ScriptView;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.dto.ShotView;
import com.dalai.llama.preprod.service.chat.ChatServiceClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CastAssignmentService castAssignmentService;
    private final CastProfileService castProfileService;
    private final ChatServiceClient chatServiceClient;

    public ProjectLockService(
            ProjectService projectService,
            ScriptGenerationService scriptGenerationService,
            ScreenplayGenerationService screenplayGenerationService,
            ShotListGenerationService shotListGenerationService,
            ShotImageService shotImageService,
            CastAssignmentService castAssignmentService,
            CastProfileService castProfileService,
            ChatServiceClient chatServiceClient
    ) {
        this.projectService = projectService;
        this.scriptGenerationService = scriptGenerationService;
        this.screenplayGenerationService = screenplayGenerationService;
        this.shotListGenerationService = shotListGenerationService;
        this.shotImageService = shotImageService;
        this.castAssignmentService = castAssignmentService;
        this.castProfileService = castProfileService;
        this.chatServiceClient = chatServiceClient;
    }

    @Transactional
    public void lock(UUID tenantId, UUID projectId) {
        ingestScript(tenantId, projectId);
        ingestScreenplay(tenantId, projectId);
        ingestCast(tenantId, projectId);
        ingestShots(tenantId, projectId);

        if (projectService.getChatSessionId(tenantId, projectId) == null) {
            UUID sessionId = chatServiceClient.createSession(tenantId, projectId);
            projectService.attachChatSession(tenantId, projectId, sessionId);
        }
        projectService.advanceStatus(tenantId, projectId, ProjectStatus.CLIENT_LOCKED);
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
        for (ShotView shot : shots) {
            List<ShotImageView> images = shotImageService.list(tenantId, shot.id());
            String imageSummary = images.isEmpty() ? "(no images generated yet)"
                    : images.stream().map(i -> i.kind().toString()).collect(Collectors.joining(", "));
            String content = "Shot " + shot.shotRef() + " (scene shot " + shot.shotNumber() + ", " + shot.shotType() + ") at " + shot.location()
                    + ": " + shot.action() + (shot.scriptLine() != null ? " | Line: " + shot.scriptLine() : "")
                    + " | Camera: " + shot.cameraShotSize() + " " + shot.cameraAngle() + " " + shot.cameraMovement()
                    + " | Lighting: " + shot.lightingMood()
                    + " | Images generated: " + imageSummary;
            chatServiceClient.ingest(tenantId, shot.id().toString(), "SHOT", projectId, content);
        }
    }
}
