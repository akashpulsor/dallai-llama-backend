package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.dto.CastAssignmentView;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.ProjectView;
import com.dalai.llama.preprod.dto.PublicProjectPackageView;
import com.dalai.llama.preprod.dto.PublicProjectPackageView.PublicCastMemberView;
import com.dalai.llama.preprod.dto.PublicProjectPackageView.PublicShotView;
import com.dalai.llama.preprod.dto.ScreenplayView;
import com.dalai.llama.preprod.dto.ScriptCharacterView;
import com.dalai.llama.preprod.dto.ScriptView;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.dto.ShotView;
import com.dalai.llama.preprod.service.chat.ChatServiceClient;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Backs {@code PublicProjectController} -- everything here resolves the (tenantId, projectId)
 * pair from the client's review token first, then delegates to the exact same tenant-scoped
 * services every authenticated page already uses. No parallel "public" read logic; this class is
 * assembly + a client-safe chat proxy, nothing more.
 */
@Service
public class PublicProjectService {

    private final ProjectService projectService;
    private final ProjectLockService projectLockService;
    private final ScriptGenerationService scriptGenerationService;
    private final ScreenplayGenerationService screenplayGenerationService;
    private final ShotListGenerationService shotListGenerationService;
    private final ShotImageService shotImageService;
    private final CastAssignmentService castAssignmentService;
    private final CastProfileService castProfileService;
    private final ChatServiceClient chatServiceClient;
    private final MinioClient minioClient;

    public PublicProjectService(
            ProjectService projectService,
            ProjectLockService projectLockService,
            ScriptGenerationService scriptGenerationService,
            ScreenplayGenerationService screenplayGenerationService,
            ShotListGenerationService shotListGenerationService,
            ShotImageService shotImageService,
            CastAssignmentService castAssignmentService,
            CastProfileService castProfileService,
            ChatServiceClient chatServiceClient,
            MinioClient minioClient
    ) {
        this.projectService = projectService;
        this.projectLockService = projectLockService;
        this.scriptGenerationService = scriptGenerationService;
        this.screenplayGenerationService = screenplayGenerationService;
        this.shotListGenerationService = shotListGenerationService;
        this.shotImageService = shotImageService;
        this.castAssignmentService = castAssignmentService;
        this.castProfileService = castProfileService;
        this.chatServiceClient = chatServiceClient;
        this.minioClient = minioClient;
    }

    @Transactional(readOnly = true)
    public PublicProjectPackageView view(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        ProjectView project = projectService.getByClientReviewToken(token);

        ScriptView script = tolerantly(() -> scriptGenerationService.get(identity.tenantId(), identity.projectId()));
        ScreenplayView screenplay = tolerantly(() -> screenplayGenerationService.get(identity.tenantId(), identity.projectId()));
        List<PublicCastMemberView> cast = script == null ? List.of() : buildCast(identity, script);
        List<PublicShotView> shots = buildShots(identity);

        return new PublicProjectPackageView(project.id(), project.name(), project.status(), script, screenplay, cast, shots);
    }

    @Transactional
    public PublicProjectPackageView lock(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        projectLockService.lock(identity.tenantId(), identity.projectId());
        return view(token);
    }

    @Transactional
    public ChatServiceClient.ChatMessageView chat(String token, String content) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        UUID sessionId = projectService.getChatSessionIdByClientReviewToken(token);
        if (sessionId == null) {
            throw PreProductionException.badRequest("This project hasn't been locked yet -- there's no chat to send to");
        }
        return chatServiceClient.sendMessage(identity.tenantId(), sessionId, content);
    }

    @Transactional(readOnly = true)
    public List<ChatServiceClient.ChatMessageView> chatHistory(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        UUID sessionId = projectService.getChatSessionIdByClientReviewToken(token);
        return sessionId == null ? List.of() : chatServiceClient.history(identity.tenantId(), sessionId);
    }

    private List<PublicCastMemberView> buildCast(ProjectService.ProjectIdentity identity, ScriptView script) {
        List<CastAssignmentView> assignments = castAssignmentService.list(identity.projectId());
        if (assignments.isEmpty()) {
            return List.of();
        }
        Map<UUID, CastProfileView> profilesById = castProfileService.list(identity.tenantId(), identity.projectId(), null).stream()
                .collect(Collectors.toMap(CastProfileView::id, p -> p));
        Map<UUID, ScriptCharacterView> charactersById = script.characters().stream()
                .collect(Collectors.toMap(ScriptCharacterView::id, c -> c));

        return assignments.stream()
                .map(a -> {
                    ScriptCharacterView character = charactersById.get(a.scriptCharacterId());
                    CastProfileView profile = profilesById.get(a.castProfileId());
                    if (character == null || profile == null) {
                        return null;
                    }
                    return new PublicCastMemberView(character.characterName(), character.characterType().toString(),
                            profile.displayName(), signedUrl(profile.faceRefBucket(), profile.faceRefObjectKey()));
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<PublicShotView> buildShots(ProjectService.ProjectIdentity identity) {
        List<ShotView> shots = shotListGenerationService.list(identity.tenantId(), identity.projectId());
        return shots.stream()
                .map(shot -> {
                    List<ShotImageView> images = shotImageService.list(identity.tenantId(), shot.id());
                    return new PublicShotView(shot.id(), shot.shotRef(), shot.shotNumber(), shot.shotType().toString(), shot.action(), images);
                })
                .collect(Collectors.toList());
    }

    private <T> T tolerantly(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (PreProductionException ex) {
            return null;
        }
    }

    private String signedUrl(String bucket, String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not sign cast image URL: " + ex.getMessage());
        }
    }
}
