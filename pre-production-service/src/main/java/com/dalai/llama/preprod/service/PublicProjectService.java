package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.dto.CastAssignmentView;
import com.dalai.llama.preprod.dto.CastProfileView;
import com.dalai.llama.preprod.dto.ProjectView;
import com.dalai.llama.preprod.dto.PublicProjectPackageView;
import com.dalai.llama.preprod.service.revenue.BillingClient;
import com.dalai.llama.preprod.dto.PublicProjectPackageView.PublicCastMemberView;
import com.dalai.llama.preprod.dto.PublicProjectPackageView.PublicShotView;
import com.dalai.llama.preprod.dto.ScreenplayView;
import com.dalai.llama.preprod.dto.ScriptCharacterView;
import com.dalai.llama.preprod.dto.ScriptView;
import com.dalai.llama.preprod.dto.ShotImageView;
import com.dalai.llama.preprod.dto.ShotView;
import com.dalai.llama.preprod.service.chat.ChatServiceClient;
import com.dalai.llama.preprod.service.videogen.VideoGenClient;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final MinioClient publicMinioClient;
    private final com.dalai.llama.preprod.service.revenue.BillingClient billingClient;
    private final ClientReviewSessionService reviewSessionService;
    private final ReviewCommentService reviewCommentService;
    private final VideoGenClient videoGenClient;

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
            MinioClient minioClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient,
            com.dalai.llama.preprod.service.revenue.BillingClient billingClient,
            ClientReviewSessionService reviewSessionService,
            ReviewCommentService reviewCommentService,
            VideoGenClient videoGenClient
    ) {
        this.reviewSessionService = reviewSessionService;
        this.reviewCommentService = reviewCommentService;
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
        this.videoGenClient = videoGenClient;
        this.publicMinioClient = publicMinioClient;
        this.billingClient = billingClient;
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

    /** The client's price to lock this package: the platform's base + the creator's own margin.
     * Read-only -- shown before the client pays. */
    public BillingClient.Quote quote(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        return billingClient.quote(identity.tenantId(), identity.projectId());
    }

    /** Starts a Razorpay order for the lock payment (via billing-service). Nothing locks yet --
     * the client pays, then {@link #verifyPaymentAndLock} runs on a verified payment. */
    public BillingClient.OrderResult startLockPayment(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        return billingClient.createOrder(identity.tenantId(), identity.projectId(), token);
    }

    /** The pay-gate: billing verifies the Razorpay signature and credits the creator's margin to
     * their wallet; only then does the project actually lock. The bare lock endpoint was removed so
     * this is the only path to a locked package. */
    @Transactional
    public PublicProjectPackageView verifyPaymentAndLock(String token, String gatewayOrderId, String gatewayPaymentId, String gatewaySignature) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        BillingClient.VerifyResult result = billingClient.verify(identity.tenantId(), gatewayOrderId, gatewayPaymentId, gatewaySignature);
        if (!result.success()) {
            throw PreProductionException.badRequest("Payment could not be verified -- the package was not locked");
        }
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
        // Reviews are transactional: a message only counts as part of a review that's been opened,
        // so all the changes batched in one review apply to the storyboard together.
        reviewSessionService.requireOpenReview(identity.projectId());
        return chatServiceClient.sendMessage(identity.tenantId(), sessionId, content);
    }

    // ---- Transactional client reviews (open -> batch changes via chat -> close/apply) ----

    /** Allowance, reviews used so far, the open review (if any), and whether starting another needs payment. */
    @Transactional(readOnly = true)
    public ClientReviewSessionService.ReviewStatus reviewStatus(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        return reviewSessionService.status(identity.tenantId(), identity.projectId());
    }

    /** Opens a review (402 if the free allowance is used up -- the client then pays to start one). */
    @Transactional
    public ClientReviewSessionService.ReviewStatus startReview(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        reviewSessionService.start(identity.tenantId(), identity.projectId(), false);
        return reviewSessionService.status(identity.tenantId(), identity.projectId());
    }

    /** Closes the open review; satisfied = apply the batched changes to the storyboard. */
    @Transactional
    public ClientReviewSessionService.ReviewStatus endReview(String token, boolean satisfied) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        reviewSessionService.end(identity.tenantId(), identity.projectId(), satisfied);
        return reviewSessionService.status(identity.tenantId(), identity.projectId());
    }

    /** Price of an extra review (billing owns the number). Shown before the client pays. */
    public BillingClient.Quote reviewQuote(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        return billingClient.reviewQuote(identity.tenantId(), identity.projectId());
    }

    /** Razorpay order for an extra-review payment. */
    public BillingClient.OrderResult startReviewPayment(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        return billingClient.createReviewOrder(identity.tenantId(), identity.projectId(), token);
    }

    /** Verifies the extra-review payment, then opens a paid review. */
    @Transactional
    public ClientReviewSessionService.ReviewStatus verifyReviewPaymentAndStart(
            String token, String gatewayOrderId, String gatewayPaymentId, String gatewaySignature) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        BillingClient.VerifyResult result = billingClient.verify(identity.tenantId(), gatewayOrderId, gatewayPaymentId, gatewaySignature);
        if (!result.success()) {
            throw PreProductionException.badRequest("Payment could not be verified -- no extra review was started");
        }
        reviewSessionService.start(identity.tenantId(), identity.projectId(), true);
        return reviewSessionService.status(identity.tenantId(), identity.projectId());
    }

    // ---- Review comments (feedback the client leaves inside an open review) ----

    /** {@code image} is optional. Requires an open review -- same "batched into a review round"
     * discipline the chat above already has. */
    @Transactional
    public com.dalai.llama.preprod.dto.ReviewCommentView addReviewComment(
            String token, String content, org.springframework.web.multipart.MultipartFile image) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        UUID reviewId = reviewSessionService.requireOpenReview(identity.projectId());
        return reviewCommentService.add(identity.tenantId(), identity.projectId(), reviewId, content, image);
    }

    @Transactional(readOnly = true)
    public List<com.dalai.llama.preprod.dto.ReviewCommentView> reviewComments(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        return reviewCommentService.list(identity.tenantId(), identity.projectId());
    }

    /** The client's view of the assembled final video. Video is always previewable when
     * available; the {@code videoUrl} field is stripped to null server-side when the creator
     * has not flipped {@link com.dalai.llama.preprod.domain.entity.Project#isFinalVideoDownloadUnlocked}
     * -- the URL never even reaches a locked client. */
    @Transactional(readOnly = true)
    public PublicFinalVideoView finalVideo(String token) {
        ProjectService.ProjectIdentity identity = projectService.resolveByClientReviewToken(token);
        ProjectView project = projectService.getByClientReviewToken(token);
        var maybe = videoGenClient.getLatestFinalVideo(identity.tenantId(), identity.projectId());
        if (maybe.isEmpty()) {
            return new PublicFinalVideoView(false, null, null, project.finalVideoDownloadUnlocked(), null);
        }
        VideoGenClient.LatestFinalVideoView view = maybe.get();
        boolean unlocked = project.finalVideoDownloadUnlocked();
        String url = unlocked ? view.videoUrl() : null;
        return new PublicFinalVideoView(view.videoUrl() != null, view.status(), url, unlocked, view.completedAt());
    }

    /** Client-facing view of the project's assembled final video -- see
     * {@link #finalVideo}. {@code available}=true means an assembly exists and is playable;
     * {@code videoUrl} is populated when download is unlocked (server-enforced), null otherwise. */
    public record PublicFinalVideoView(
            boolean available,
            String status,
            String videoUrl,
            boolean downloadUnlocked,
            java.time.OffsetDateTime completedAt
    ) {}

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

    /** Display-only for the public client-review page's cast list -- degrades to no image rather
     * than 500ing the whole page. Both null-guarded (an AI-generated-identity cast profile has no
     * face_ref_bucket/object_key at all, see V54 migration) and catch-and-return-null on presign
     * failure, same convention as {@code CastProfileService.signedUrl} and {@code
     * ShotListGenerationService.signedFaceUrl}. */
    private String signedUrl(String bucket, String objectKey) {
        if (bucket == null || objectKey == null) {
            return null;
        }
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            return null;
        }
    }
}
