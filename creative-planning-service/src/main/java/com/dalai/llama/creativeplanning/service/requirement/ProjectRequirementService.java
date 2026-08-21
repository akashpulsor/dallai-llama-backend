package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.TenantType;
import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.dto.CreateRequirementFromIdeaRequest;
import com.dalai.llama.creativeplanning.dto.CreateStandaloneRequirementRequest;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementView;
import com.dalai.llama.creativeplanning.dto.PublicProjectRequirementView;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementRepository;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One flow, two entry points -- see {@link ProjectRequirement}'s own javadoc for the design
 * rationale. {@code funded} is a recorded state, never a payment this class executes: no method
 * here talks to a payment provider.
 * <p>
 * The share link is time-boxed ({@code shareTokenTtlDays}, configurable) -- the {@code
 * *ByShareToken} methods (called from the unauthenticated {@code PublicProjectRequirementController})
 * refuse an expired token, and {@link #refreshShareToken} is the "the client didn't pay in time,
 * please resend" path: it issues a brand-new token and pushes the expiry forward, which also
 * invalidates whatever link was sent before -- the old token simply stops resolving once the
 * column is overwritten.
 */
@Service
public class ProjectRequirementService {

    private final ProjectRequirementRepository projectRequirementRepository;
    private final LockedIdeaRepository lockedIdeaRepository;
    private final long shareTokenTtlDays;
    private final String paymentWebhookSecret;

    public ProjectRequirementService(
            ProjectRequirementRepository projectRequirementRepository,
            LockedIdeaRepository lockedIdeaRepository,
            @Value("${creative-planning.share-token.ttl-days}") long shareTokenTtlDays,
            @Value("${creative-planning.payment-webhook.secret:}") String paymentWebhookSecret
    ) {
        this.projectRequirementRepository = projectRequirementRepository;
        this.lockedIdeaRepository = lockedIdeaRepository;
        this.shareTokenTtlDays = shareTokenTtlDays;
        this.paymentWebhookSecret = paymentWebhookSecret;
    }

    /** Entry point A: from a locked idea a brand's campaign-planning chat already produced. */
    @Transactional
    public ProjectRequirementView createFromLockedIdea(UUID tenantId, UUID userId, UUID lockedIdeaId, CreateRequirementFromIdeaRequest request) {
        LockedIdea idea = lockedIdeaRepository.findByIdAndTenantId(lockedIdeaId, tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No locked idea " + lockedIdeaId));

        String brief = "%s\n\n%s".formatted(idea.getTitle(), idea.getConcept() == null ? "" : idea.getConcept());
        ProjectRequirement requirement = build(tenantId, userId, request.tenantType(), idea.getId(), brief,
                idea.getTargetAudience(), idea.getCampaignAngle(), idea.getBudgetTier());
        return toView(projectRequirementRepository.save(requirement));
    }

    /** Entry point B: a standalone brief with no branding exercise behind it. */
    @Transactional
    public ProjectRequirementView createStandalone(UUID tenantId, UUID userId, CreateStandaloneRequirementRequest request) {
        ProjectRequirement requirement = build(tenantId, userId, request.tenantType(), null, request.briefText(),
                request.targetAudience(), request.campaignDirection(), request.budgetTier());
        return toView(projectRequirementRepository.save(requirement));
    }

    @Transactional(readOnly = true)
    public ProjectRequirementView get(UUID tenantId, UUID requirementId) {
        return toView(requireRequirement(tenantId, requirementId));
    }

    @Transactional(readOnly = true)
    public List<ProjectRequirementView> list(UUID tenantId) {
        return projectRequirementRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    /** Tenant-side funding confirmation, e.g. the creator recording an out-of-band payment
     * themselves. See {@link #markFundedByShareToken} for the unauthenticated variant a real
     * payment webhook would call. Neither talks to a payment provider -- see the class javadoc. */
    @Transactional
    public ProjectRequirementView markFunded(UUID tenantId, UUID requirementId, UUID fundedByUserId) {
        return toView(applyFunding(requireRequirement(tenantId, requirementId), fundedByUserId));
    }

    /** "Client didn't pay in time, please resend" -- a new token, a new expiry, and the old link
     * stops working. Same brief, same requirement, just a fresh door into it. */
    @Transactional
    public ProjectRequirementView refreshShareToken(UUID tenantId, UUID requirementId) {
        ProjectRequirement requirement = requireRequirement(tenantId, requirementId);
        OffsetDateTime now = OffsetDateTime.now();
        requirement.setShareToken(generateToken());
        requirement.setShareTokenExpiresAt(now.plusDays(shareTokenTtlDays));
        requirement.setUpdatedAt(now);
        return toView(projectRequirementRepository.save(requirement));
    }

    /** Unauthenticated read -- backs {@code GET /v1/public/project-requirements/{shareToken}}.
     * Deliberately returns the slimmer {@link PublicProjectRequirementView} (no tenant id, no
     * created-by, no locked-idea cross-reference) since this is reachable by anyone holding the
     * link, not just the tenant. */
    @Transactional(readOnly = true)
    public PublicProjectRequirementView getByShareToken(String shareToken) {
        return toPublicView(requireLiveByShareToken(shareToken));
    }

    /**
     * The real target for this is a payment provider's webhook, once one is integrated -- not
     * the share-link visitor themselves. The share token alone is <b>not</b> sufficient
     * authorization here: whoever holds the link (including the person being asked to pay) could
     * otherwise call this and mark themselves funded without paying anything. {@code
     * webhookSecret} must match {@code creative-planning.payment-webhook.secret}, which is blank
     * by default -- with no payment gateway wired up yet, that makes this endpoint reject every
     * call until an operator configures a real secret shared with an actual payment provider.
     * Still never processes a payment itself -- see the class javadoc.
     */
    @Transactional
    public PublicProjectRequirementView markFundedByShareToken(String shareToken, String webhookSecret, UUID fundedByUserId) {
        if (paymentWebhookSecret.isBlank() || !paymentWebhookSecret.equals(webhookSecret)) {
            throw CreativePlanningException.forbidden("Funding confirmation requires a valid payment-webhook secret");
        }
        return toPublicView(applyFunding(requireLiveByShareToken(shareToken), fundedByUserId));
    }

    ProjectRequirement requireRequirement(UUID tenantId, UUID requirementId) {
        return projectRequirementRepository.findByIdAndTenantId(requirementId, tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No project requirement " + requirementId));
    }

    private ProjectRequirement requireLiveByShareToken(String shareToken) {
        ProjectRequirement requirement = projectRequirementRepository.findByShareToken(shareToken)
                .orElseThrow(() -> CreativePlanningException.notFound("No requirement for this link"));
        if (requirement.getShareTokenExpiresAt().isBefore(OffsetDateTime.now())) {
            throw CreativePlanningException.gone("This link has expired -- ask the creator to resend it");
        }
        return requirement;
    }

    private ProjectRequirement applyFunding(ProjectRequirement requirement, UUID fundedByUserId) {
        if (requirement.getTenantType() == TenantType.COMPANY) {
            throw CreativePlanningException.badRequest("COMPANY requirements are funded via wallet, not per-project payment");
        }
        requirement.setFunded(true);
        requirement.setFundedBy(fundedByUserId);
        requirement.setUpdatedAt(OffsetDateTime.now());
        return projectRequirementRepository.save(requirement);
    }

    private ProjectRequirement build(UUID tenantId, UUID userId, TenantType tenantType, UUID lockedIdeaId, String brief,
                                      String targetAudience, String campaignDirection, BudgetTier budgetTier) {
        OffsetDateTime now = OffsetDateTime.now();
        boolean fundedAtCreation = tenantType == TenantType.COMPANY;
        return ProjectRequirement.builder()
                .tenantId(tenantId)
                .tenantType(tenantType)
                .lockedIdeaId(lockedIdeaId)
                .briefText(brief)
                .targetAudience(targetAudience)
                .campaignDirection(campaignDirection)
                .budgetTier(budgetTier)
                .shareToken(generateToken())
                .shareTokenExpiresAt(now.plusDays(shareTokenTtlDays))
                .createdBy(userId)
                .funded(fundedAtCreation)
                .fundedBy(fundedAtCreation ? userId : null)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private ProjectRequirementView toView(ProjectRequirement requirement) {
        return new ProjectRequirementView(requirement.getId(), requirement.getTenantType(), requirement.getLockedIdeaId(),
                requirement.getBriefText(), requirement.getTargetAudience(), requirement.getCampaignDirection(),
                requirement.getBudgetTier(), requirement.getShareToken(), requirement.getShareTokenExpiresAt(),
                requirement.isFunded(), requirement.getFundedBy(), requirement.getCreatedAt());
    }

    private PublicProjectRequirementView toPublicView(ProjectRequirement requirement) {
        return new PublicProjectRequirementView(requirement.getBriefText(), requirement.getTargetAudience(),
                requirement.getCampaignDirection(), requirement.getBudgetTier(), requirement.isFunded());
    }
}
