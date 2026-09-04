package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.ClientReviewSession;
import com.dalai.llama.preprod.domain.entity.Project;
import com.dalai.llama.preprod.repository.ClientReviewSessionRepository;
import com.dalai.llama.preprod.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Transactional client reviews. A review is opened once, accumulates changes through the review
 * chat, and is closed as one transaction -- so a chat message never counts as its own review. The
 * number of STARTED reviews counts against {@link Project#getReviewAllowance()}; beyond it, opening
 * a new review requires an extra-review payment (which sets {@code paid=true} on that review).
 */
@Service
public class ClientReviewSessionService {

    private static final String OPEN = "OPEN";
    private static final String CLOSED = "CLOSED";

    private final ClientReviewSessionRepository reviewRepository;
    private final ProjectRepository projectRepository;

    public ClientReviewSessionService(ClientReviewSessionRepository reviewRepository,
                                      ProjectRepository projectRepository) {
        this.reviewRepository = reviewRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional(readOnly = true)
    public ReviewStatus status(UUID tenantId, UUID projectId) {
        Project project = project(tenantId, projectId);
        long used = reviewRepository.countByProjectId(projectId);
        UUID openId = reviewRepository.findFirstByProjectIdAndStatus(projectId, OPEN)
                .map(ClientReviewSession::getId).orElse(null);
        boolean paymentRequired = used >= project.getReviewAllowance();
        return new ReviewStatus(project.getReviewAllowance(), (int) used, openId, paymentRequired, project.isReviewsEnabled());
    }

    /**
     * Opens a review. If one is already open, that same review is returned (idempotent -- the
     * client is still in it). Beyond the allowance an unpaid open is refused with 402; {@code paid}
     * = true (the payment-verify path) bypasses the gate and marks the review as paid.
     */
    @Transactional
    public ClientReviewSession start(UUID tenantId, UUID projectId, boolean paid) {
        Project project = project(tenantId, projectId);
        if (!project.isReviewsEnabled()) {
            throw PreProductionException.conflict("The creator has closed client reviews for this project.");
        }
        ClientReviewSession open = reviewRepository.findFirstByProjectIdAndStatus(projectId, OPEN).orElse(null);
        if (open != null) {
            return open;
        }
        long used = reviewRepository.countByProjectId(projectId);
        if (!paid && used >= project.getReviewAllowance()) {
            throw PreProductionException.paymentRequired(
                    "You've used all %d included reviews for this project. Pay to start another review."
                            .formatted(project.getReviewAllowance()));
        }
        return reviewRepository.save(ClientReviewSession.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .status(OPEN)
                .paid(paid)
                .startedAt(OffsetDateTime.now())
                .build());
    }

    /**
     * Closes the open review as one transaction. {@code satisfied} = the batched changes are to be
     * applied to the storyboard (outcome SATISFIED); otherwise the review is closed as
     * CHANGES_REQUESTED. Returns the closed review so the caller can trigger the apply.
     */
    @Transactional
    public ClientReviewSession end(UUID tenantId, UUID projectId, boolean satisfied) {
        project(tenantId, projectId);
        ClientReviewSession open = reviewRepository.findFirstByProjectIdAndStatus(projectId, OPEN)
                .orElseThrow(() -> PreProductionException.badRequest("There is no open review to end."));
        open.setStatus(CLOSED);
        open.setOutcome(satisfied ? "SATISFIED" : "CHANGES_REQUESTED");
        open.setEndedAt(OffsetDateTime.now());
        return reviewRepository.save(open);
    }

    /** The open review's id, or throws if the client hasn't started a review -- gates chat/change
     * requests so they always land inside an open transactional review. */
    @Transactional(readOnly = true)
    public UUID requireOpenReview(UUID projectId) {
        return reviewRepository.findFirstByProjectIdAndStatus(projectId, OPEN)
                .map(ClientReviewSession::getId)
                .orElseThrow(() -> PreProductionException.badRequest(
                        "Start a review before requesting changes -- reviews are applied to the storyboard as one batch."));
    }

    private Project project(UUID tenantId, UUID projectId) {
        return projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("Unknown project: " + projectId));
    }

    public record ReviewStatus(int allowance, int used, UUID openReviewId, boolean paymentRequiredToStart, boolean reviewsEnabled) {}
}
