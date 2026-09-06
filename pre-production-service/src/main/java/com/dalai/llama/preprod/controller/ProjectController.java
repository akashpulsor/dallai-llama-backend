package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.domain.ProjectStatus;
import com.dalai.llama.preprod.dto.ClientReviewLinkView;
import com.dalai.llama.preprod.dto.CreateProjectRequest;
import com.dalai.llama.preprod.dto.ProjectView;
import com.dalai.llama.preprod.dto.ReviewCommentView;
import com.dalai.llama.preprod.dto.ShotDesignReadyProjectView;
import com.dalai.llama.preprod.service.ProjectLockService;
import com.dalai.llama.preprod.service.ProjectService;
import com.dalai.llama.preprod.service.ReviewCommentService;
import com.dalai.llama.preprod.service.ShotDesignReadyProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ProjectController extends BaseController {

    private final ProjectService projectService;
    private final ProjectLockService projectLockService;
    private final ReviewCommentService reviewCommentService;
    private final ShotDesignReadyProjectService shotDesignReadyProjectService;

    public ProjectController(
            ProjectService projectService,
            ProjectLockService projectLockService,
            ReviewCommentService reviewCommentService,
            ShotDesignReadyProjectService shotDesignReadyProjectService
    ) {
        this.projectService = projectService;
        this.projectLockService = projectLockService;
        this.reviewCommentService = reviewCommentService;
        this.shotDesignReadyProjectService = shotDesignReadyProjectService;
    }

    @PostMapping("/v1/projects/from-locked-idea")
    public ResponseEntity<ProjectView> createFromLockedIdea(@Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.ok(projectService.createFromLockedIdea(tenant().tenantId(), request));
    }

    @GetMapping("/v1/projects/{projectId}")
    public ResponseEntity<ProjectView> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(projectService.get(tenant().tenantId(), projectId));
    }

    @GetMapping("/v1/projects")
    public ResponseEntity<List<ProjectView>> list() {
        return ResponseEntity.ok(projectService.list(tenant().tenantId()));
    }

    /** creator-ui's Planner page "post production" panel: every recent project that has at least
     * one shot, each with its shots inline -- see {@link ShotDesignReadyProjectView}'s class
     * comment. Distinct literal segment "shot-design-ready" is matched before the
     * {@code /v1/projects/{projectId}} variable mapping above (standard Spring path-matching
     * precedence), so this doesn't collide with it. */
    @GetMapping("/v1/projects/shot-design-ready")
    public ResponseEntity<List<ShotDesignReadyProjectView>> listShotDesignReady(
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(shotDesignReadyProjectService.list(tenant().tenantId(), limit));
    }

    /** Idempotent -- returns the existing client review link if one was already generated. The
     * creator shares this link with their client out of band (copy/paste); it opens the
     * unauthenticated {@code GET /v1/public/projects/{token}} page. */
    @PostMapping("/v1/projects/{projectId}/client-review-link")
    public ResponseEntity<ClientReviewLinkView> ensureClientReviewLink(@PathVariable UUID projectId) {
        return ResponseEntity.ok(new ClientReviewLinkView(projectService.ensureClientReviewToken(tenant().tenantId(), projectId)));
    }

    /** Creator control over this project's client reviews: set the included allowance and/or turn
     * reviews on/off on demand. Both fields optional -- omit one to leave it unchanged. */
    @PatchMapping("/v1/projects/{projectId}/review-settings")
    public ResponseEntity<ProjectView> updateReviewSettings(
            @PathVariable UUID projectId, @RequestBody ReviewSettingsRequest request) {
        return ResponseEntity.ok(projectService.updateReviewSettings(
                tenant().tenantId(), projectId,
                request == null ? null : request.reviewAllowance(),
                request == null ? null : request.reviewsEnabled()));
    }

    public record ReviewSettingsRequest(Integer reviewAllowance, Boolean reviewsEnabled) {}

    /** Manual creator toggle for whether the client can download the assembled final video from
     * their public review page. Client preview is always allowed; this gates the download link. */
    @PatchMapping("/v1/projects/{projectId}/final-video-lock")
    public ResponseEntity<ProjectView> updateFinalVideoLock(
            @PathVariable UUID projectId, @Valid @RequestBody FinalVideoLockRequest request) {
        return ResponseEntity.ok(projectService.updateFinalVideoDownloadUnlocked(
                tenant().tenantId(), projectId, request.unlocked()));
    }

    public record FinalVideoLockRequest(@NotNull Boolean unlocked) {}

    /** Frontend-driven stage transitions (e.g. VIDEO_GENERATION_COMPLETE once creator-ui observes
     * every shot COMPLETED) -- {@link ProjectStateMachine} still enforces which transitions are
     * legal from the project's current status, this endpoint can't be used to skip stages. */
    @PatchMapping("/v1/projects/{projectId}/status")
    public ResponseEntity<ProjectView> updateStatus(@PathVariable UUID projectId, @Valid @RequestBody UpdateStatusRequest request) {
        projectService.advanceStatus(tenant().tenantId(), projectId, request.target());
        return ResponseEntity.ok(projectService.get(tenant().tenantId(), projectId));
    }

    public record UpdateStatusRequest(@NotNull ProjectStatus target) {}

    /** Refreshes this project's chat-embedding context (script/screenplay/cast/shots) and opens a
     * chat session if one doesn't exist yet -- lets the creator's "chat about a shot" panel work
     * before any client has paid to lock the package. See {@link ProjectLockService#syncChatContext}. */
    @PostMapping("/v1/projects/{projectId}/chat/sync")
    public ResponseEntity<Void> syncChatContext(@PathVariable UUID projectId) {
        projectLockService.syncChatContext(tenant().tenantId(), projectId);
        return ResponseEntity.noContent().build();
    }

    /** The client's review feedback (comment + optional reference image), newest first -- see
     * {@link ReviewCommentService}. */
    @GetMapping("/v1/projects/{projectId}/review-comments")
    public ResponseEntity<List<ReviewCommentView>> listReviewComments(@PathVariable UUID projectId) {
        return ResponseEntity.ok(reviewCommentService.list(tenant().tenantId(), projectId));
    }

    /** Marks feedback as handled -- the creator actually makes the fix through the normal shot-
     * chat/apply flow, then resolves the comment here once done. */
    @PostMapping("/v1/projects/{projectId}/review-comments/{commentId}/resolve")
    public ResponseEntity<ReviewCommentView> resolveReviewComment(@PathVariable UUID projectId, @PathVariable UUID commentId) {
        return ResponseEntity.ok(reviewCommentService.resolve(tenant().tenantId(), projectId, commentId));
    }
}
