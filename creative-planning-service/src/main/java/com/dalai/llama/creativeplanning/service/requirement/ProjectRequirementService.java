package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.TenantType;
import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningSession;
import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.dto.CreateRequirementFromIdeaRequest;
import com.dalai.llama.creativeplanning.dto.CreateStandaloneRequirementRequest;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementView;
import com.dalai.llama.creativeplanning.dto.PublicProjectRequirementView;
import com.dalai.llama.creativeplanning.dto.UpdateRequirementQuoteRequest;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningSessionRepository;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementRepository;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Arrays;
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
    private final CampaignPlanningSessionRepository campaignPlanningSessionRepository;
    private final BrandContextService brandContextService;
    private final BillingServiceClient billingServiceClient;
    private final RequirementFundingBillingClient requirementFundingBillingClient;
    private final ReferenceMaterialAnalysisService referenceMaterialAnalysisService;
    private final long shareTokenTtlDays;
    private final String paymentWebhookSecret;

    public ProjectRequirementService(
            ProjectRequirementRepository projectRequirementRepository,
            LockedIdeaRepository lockedIdeaRepository,
            CampaignPlanningSessionRepository campaignPlanningSessionRepository,
            BrandContextService brandContextService,
            BillingServiceClient billingServiceClient,
            RequirementFundingBillingClient requirementFundingBillingClient,
            ReferenceMaterialAnalysisService referenceMaterialAnalysisService,
            @Value("${creative-planning.share-token.ttl-days}") long shareTokenTtlDays,
            @Value("${creative-planning.payment-webhook.secret:}") String paymentWebhookSecret
    ) {
        this.projectRequirementRepository = projectRequirementRepository;
        this.lockedIdeaRepository = lockedIdeaRepository;
        this.campaignPlanningSessionRepository = campaignPlanningSessionRepository;
        this.brandContextService = brandContextService;
        this.billingServiceClient = billingServiceClient;
        this.requirementFundingBillingClient = requirementFundingBillingClient;
        this.referenceMaterialAnalysisService = referenceMaterialAnalysisService;
        this.shareTokenTtlDays = shareTokenTtlDays;
        this.paymentWebhookSecret = paymentWebhookSecret;
    }

    /** Entry point A: from a locked idea a brand's campaign-planning chat already produced --
     * that chat's session already picked a specific brand (see {@code CampaignSessionService
     * #create}), so this just carries that same brandContextId forward rather than asking again. */
    @Transactional
    public ProjectRequirementView createFromLockedIdea(UUID tenantId, UUID userId, UUID lockedIdeaId, CreateRequirementFromIdeaRequest request) {
        LockedIdea idea = lockedIdeaRepository.findByIdAndTenantId(lockedIdeaId, tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No locked idea " + lockedIdeaId));
        UUID brandContextId = campaignPlanningSessionRepository.findByIdAndTenantId(idea.getSessionId(), tenantId)
                .map(CampaignPlanningSession::getBrandContextId)
                .orElse(null);

        String brief = "%s\n\n%s".formatted(idea.getTitle(), idea.getConcept() == null ? "" : idea.getConcept());
        ProjectRequirement requirement = build(tenantId, userId, request.tenantType(), idea.getId(), brandContextId, brief,
                idea.getTargetAudience(), idea.getCampaignAngle(), idea.getBudgetTier(), null, null, null);
        return toView(projectRequirementRepository.save(requirement));
    }

    /** Entry point B: a standalone brief with no branding exercise behind it, priced by duration
     * rather than a picked tier. Exactly one of {@code request.brandId()} (an existing brand --
     * optionally also updated in place if {@code brandContext} is given alongside it) or {@code
     * request.brandContext()} alone (creates a brand-new brand) is expected; a creator with no
     * brand at all yet may give neither. {@code durationSeconds} is quoted against billing-
     * service's per-second platform rate plus this creator's own margin (see {@link
     * BillingServiceClient}) and the quote is snapshotted onto the saved requirement. */
    @Transactional
    public ProjectRequirementView createStandalone(UUID tenantId, UUID userId, CreateStandaloneRequirementRequest request) {
        UUID brandContextId = resolveOrCreateBrand(tenantId, request.brandId(), request.brandContext());
        BillingServiceClient.VideoPriceQuote quote = billingServiceClient.quoteVideoPrice(tenantId, request.durationSeconds());
        ProjectRequirement requirement = build(tenantId, userId, request.tenantType(), null, brandContextId, request.briefText(),
                request.targetAudience(), request.campaignDirection(), deriveLegacyBudgetTier(request.durationSeconds()),
                request.durationSeconds(), String.join(",", request.languages()), quote);
        return toView(projectRequirementRepository.save(requirement));
    }

    /** {@code brandId} given -> that existing brand (also edited in place if {@code brandContext}
     * is given alongside it). {@code brandId} absent but {@code brandContext} given -> a brand-new
     * brand. Neither given -> null (no brand at all, still a valid brief). */
    private UUID resolveOrCreateBrand(UUID tenantId, UUID brandId, com.dalai.llama.creativeplanning.dto.CreateBrandContextRequest brandContext) {
        if (brandId != null) {
            if (brandContext != null) {
                return brandContextService.update(tenantId, brandId, brandContext).id();
            }
            return brandContextService.requireBrand(tenantId, brandId).getId();
        }
        if (brandContext != null) {
            return brandContextService.create(tenantId, brandContext).id();
        }
        return null;
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

    /** Every brief/project created for one specific brand -- the Brands tab's "projects" list. */
    @Transactional(readOnly = true)
    public List<ProjectRequirementView> listByBrand(UUID tenantId, UUID brandId) {
        brandContextService.requireBrand(tenantId, brandId); // ownership check
        return projectRequirementRepository.findByTenantIdAndBrandContextIdOrderByCreatedAtDesc(tenantId, brandId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    /** Tenant-side funding confirmation, e.g. the creator recording an out-of-band payment
     * themselves. See {@link #markFundedByShareToken} for the unauthenticated variant a real
     * payment webhook would call. Neither talks to a payment provider -- see the class javadoc.
     * Also kicks off {@link ReferenceMaterialAnalysisService}, same as {@link
     * #markFundedFromPayment} -- see that method's javadoc for why funding, not upload, is the
     * trigger. */
    @Transactional
    public ProjectRequirementView markFunded(UUID tenantId, UUID requirementId, UUID fundedByUserId) {
        ProjectRequirement requirement = applyFunding(requireRequirement(tenantId, requirementId), fundedByUserId);
        referenceMaterialAnalysisService.analyzePendingForRequirement(tenantId, requirement.getId());
        return toView(requirement);
    }

    /**
     * The real, event-driven funding path: billing-service confirms a Razorpay payment (via its
     * webhook, not the client-callback path) and publishes {@code billing.payment.received} with
     * this requirement's id. Unlike {@link #markFunded}, this is never a self-report -- it only
     * ever fires because money actually moved. Idempotent under Kafka redelivery: a
     * requirement that's already funded is left alone rather than re-applying the update or
     * throwing.
     * <p>
     * This is also the trigger for {@link ReferenceMaterialAnalysisService} -- a standalone
     * requirement's product/project reference images are stored at creation time but only
     * analyzed once the requirement is actually funded, not on upload, since that's the point a
     * standalone brief's images are worth the LLM cost. {@link ReferenceMaterialAnalysisService}
     * is itself idempotent per image, so redelivery is safe even though this method's own
     * idempotency check above returns early on an already-funded requirement.
     */
    @Transactional
    public void markFundedFromPayment(UUID tenantId, UUID requirementId, UUID paymentId) {
        ProjectRequirement requirement = requireRequirement(tenantId, requirementId);
        if (requirement.isFunded()) {
            return;
        }
        applyFunding(requirement, null);
        referenceMaterialAnalysisService.analyzePendingForRequirement(tenantId, requirementId);
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

    /** A creator overriding their own auto-computed quote and/or asking for only a partial
     * payment upfront -- {@code quotedPlatformCost} (the creator's actual cost) is never touched
     * here, only the client-facing total and how much of it is required now. Refused once funded,
     * same invariant as every other pre-funding-only mutation on this resource: past that point
     * real money has already moved against the old terms.
     * <p>
     * {@code quotedCreatorMarginPercent} IS recomputed here, from the overridden total against
     * the still-real {@code quotedPlatformCost} -- without this, a sales-negotiated override (e.g.
     * a client who will only pay a fixed amount well under the formula's output) leaves the stored
     * margin describing a price nobody is actually charging, which is exactly the number a creator
     * would look at to decide whether the deal is still profitable. */
    @Transactional
    public ProjectRequirementView updateQuote(UUID tenantId, UUID requirementId, UpdateRequirementQuoteRequest request) {
        ProjectRequirement requirement = requireRequirement(tenantId, requirementId);
        if (requirement.isFunded()) {
            throw CreativePlanningException.badRequest("This requirement is already funded and its price can no longer be changed");
        }
        requirement.setQuotedTotalPrice(request.totalPrice());
        requirement.setRequiredPaymentPercent(request.requiredPaymentPercent());
        BigDecimal platformCost = requirement.getQuotedPlatformCost();
        if (platformCost != null && platformCost.signum() > 0 && request.totalPrice() != null) {
            BigDecimal impliedMargin = request.totalPrice().subtract(platformCost)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(platformCost, 2, RoundingMode.HALF_UP);
            requirement.setQuotedCreatorMarginPercent(impliedMargin);
        }
        requirement.setUpdatedAt(OffsetDateTime.now());
        return toView(projectRequirementRepository.save(requirement));
    }

    /** billing-service's per-project spend cap needs the price a project was actually sold at --
     * there is no direct FK from pre-production-service's {@code Project.id} back to this
     * service's {@code ProjectRequirement}; the join is {@code Project.id == LockedIdea.projectId
     * -> LockedIdea.projectRequirementId -> ProjectRequirement}, entirely within this service's
     * own tables (see {@link LockedIdea}'s own javadoc on the handoff). Returns null if this
     * project didn't originate from a quoted requirement (e.g. no requirement/quote flow was
     * used) rather than throwing -- an absent quote is a legitimate case for the caller to handle,
     * not an error here. */
    @Transactional(readOnly = true)
    public BigDecimal findQuotedTotalPrice(UUID tenantId, UUID projectId) {
        return lockedIdeaRepository.findTopByProjectIdOrderByCreatedAtDesc(projectId)
                .filter(idea -> idea.getTenantId().equals(tenantId))
                .map(LockedIdea::getProjectRequirementId)
                .flatMap(projectRequirementRepository::findById)
                .map(ProjectRequirement::getQuotedTotalPrice)
                .orElse(null);
    }

    /** Unauthenticated read -- backs {@code GET /v1/public/project-requirements/{shareToken}}.
     * Deliberately returns the slimmer {@link PublicProjectRequirementView} (no tenant id, no
     * created-by, no locked-idea cross-reference) since this is reachable by anyone holding the
     * link, not just the tenant. */
    @Transactional(readOnly = true)
    public PublicProjectRequirementView getByShareToken(String shareToken) {
        return toPublicView(requireLiveByShareToken(shareToken));
    }

    /** The public brief page also shows the requirement's brand context, product, and reference
     * images -- those live in other services' repositories, so the controller assembles them
     * itself; this just resolves which tenant/requirement a share token actually points to. */
    @Transactional(readOnly = true)
    public RequirementIdentity identifyByShareToken(String shareToken) {
        ProjectRequirement requirement = requireLiveByShareToken(shareToken);
        return new RequirementIdentity(requirement.getTenantId(), requirement.getId(), requirement.getBrandContextId());
    }

    public record RequirementIdentity(UUID tenantId, UUID requirementId, UUID brandContextId) {}

    /** The client edited brand fields on the brief page but this requirement had no brand yet --
     * creates one and attaches it, so every future edit (and the analysis/ideation prompts) has a
     * real brand to point at instead of creating a fresh, orphaned brand on every edit. */
    @Transactional
    public UUID attachNewBrand(UUID tenantId, UUID requirementId, com.dalai.llama.creativeplanning.dto.CreateBrandContextRequest brandContext) {
        ProjectRequirement requirement = requireRequirement(tenantId, requirementId);
        UUID brandId = brandContextService.create(tenantId, brandContext).id();
        requirement.setBrandContextId(brandId);
        requirement.setUpdatedAt(OffsetDateTime.now());
        projectRequirementRepository.save(requirement);
        return brandId;
    }

    /** Lets whoever holds the share link edit the brief itself -- the creator may send a mostly-
     * blank brief and have the client fill in what they actually want, or the client may just want
     * to correct/add detail before paying. Each field is applied only when given (a blank/omitted
     * one leaves the existing value alone, so the client isn't forced to retype everything to fix
     * one line). Refused once funded -- past that point the brief has already been used to
     * generate ideas from, and duration/languages/price stay fixed regardless (those drive the
     * quote, not the creative brief). */
    @Transactional
    public PublicProjectRequirementView updateFromClient(String shareToken, String briefText, String targetAudience, String campaignDirection) {
        ProjectRequirement requirement = requireLiveByShareToken(shareToken);
        if (requirement.isFunded()) {
            throw CreativePlanningException.badRequest("This brief is already funded and can no longer be edited");
        }
        if (briefText != null && !briefText.isBlank()) {
            requirement.setBriefText(briefText);
        }
        if (targetAudience != null) {
            requirement.setTargetAudience(targetAudience);
        }
        if (campaignDirection != null) {
            requirement.setCampaignDirection(campaignDirection);
        }
        requirement.setClientUpdatedAt(OffsetDateTime.now());
        requirement.setUpdatedAt(OffsetDateTime.now());
        return toPublicView(projectRequirementRepository.save(requirement));
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

    /** Starts a real Razorpay order for the exact price already quoted and shown on the brief
     * (see {@link #createStandalone}) -- no re-quoting here, the visitor pays what they were shown.
     * Rejects an already-funded or never-quoted (entry-point-A) requirement outright rather than
     * charging a stale or missing amount. */
    @Transactional
    public RequirementPaymentOrder startPayment(String shareToken) {
        ProjectRequirement requirement = requireLiveByShareToken(shareToken);
        if (requirement.isFunded()) {
            throw CreativePlanningException.badRequest("This brief is already funded");
        }
        if (requirement.getQuotedTotalPrice() == null) {
            throw CreativePlanningException.badRequest("This brief has no price to pay");
        }
        RequirementFundingBillingClient.OrderResult order = requirementFundingBillingClient.createOrder(
                requirement.getTenantId(), requirement.getId(), requirement.getRequiredAmount(),
                requirement.getQuotedCurrency(), "Brief funding: " + requirement.getId());
        return new RequirementPaymentOrder(order.paymentId(), order.gatewayOrderId(), order.amount(), order.currency(), order.keyId());
    }

    /** Verifies the client's Razorpay callback, then applies the exact same funding transition
     * {@link #markFundedFromPayment} does (also reachable, redundantly but harmlessly, via the
     * async {@code billing.payment.received} Kafka event once the webhook fires) -- whichever
     * arrives first wins, the other is a no-op thanks to that method's own idempotency check. */
    @Transactional
    public PublicProjectRequirementView verifyPayment(String shareToken, UUID paymentId, String gatewayOrderId,
                                                        String gatewayPaymentId, String gatewaySignature) {
        ProjectRequirement requirement = requireLiveByShareToken(shareToken);
        requirementFundingBillingClient.verify(requirement.getTenantId(), paymentId, gatewayOrderId, gatewayPaymentId, gatewaySignature);
        markFundedFromPayment(requirement.getTenantId(), requirement.getId(), paymentId);
        return toPublicView(requireRequirement(requirement.getTenantId(), requirement.getId()));
    }

    public record RequirementPaymentOrder(UUID paymentId, String gatewayOrderId, java.math.BigDecimal amount, String currency, String keyId) {}

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

    private ProjectRequirement build(UUID tenantId, UUID userId, TenantType tenantType, UUID lockedIdeaId, UUID brandContextId, String brief,
                                      String targetAudience, String campaignDirection, BudgetTier budgetTier,
                                      Integer durationSeconds, String languages, BillingServiceClient.VideoPriceQuote quote) {
        OffsetDateTime now = OffsetDateTime.now();
        boolean fundedAtCreation = tenantType == TenantType.COMPANY;
        return ProjectRequirement.builder()
                .tenantId(tenantId)
                .tenantType(tenantType)
                .lockedIdeaId(lockedIdeaId)
                .brandContextId(brandContextId)
                .briefText(brief)
                .targetAudience(targetAudience)
                .campaignDirection(campaignDirection)
                .budgetTier(budgetTier)
                .durationSeconds(durationSeconds)
                .languages(languages)
                .quotedPlatformCost(quote == null ? null : quote.platformCost())
                .quotedCreatorMarginPercent(quote == null ? null : quote.creatorMarginPercent())
                .quotedTotalPrice(quote == null ? null : quote.totalPrice())
                .quotedCurrency(quote == null ? null : quote.currency())
                .shareToken(generateToken())
                .shareTokenExpiresAt(now.plusDays(shareTokenTtlDays))
                .createdBy(userId)
                .funded(fundedAtCreation)
                .fundedBy(fundedAtCreation ? userId : null)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** Internal-only compatibility mapping -- a standalone requirement's creator never picks or
     * sees a tier (see {@link ProjectRequirement}'s javadoc); this exists purely so downstream
     * consumers still keyed off the legacy LEAN/STANDARD/PREMIUM tiers ({@code
     * ProjectRequirementIdeaService}'s LLM prompt context, pre-production-service's own {@code
     * Project.budgetTier}) keep working without their own migration. */
    private static BudgetTier deriveLegacyBudgetTier(int durationSeconds) {
        if (durationSeconds <= 15) {
            return BudgetTier.LEAN;
        }
        if (durationSeconds <= 45) {
            return BudgetTier.STANDARD;
        }
        return BudgetTier.PREMIUM;
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private ProjectRequirementView toView(ProjectRequirement requirement) {
        UUID lockedProjectId = lockedIdeaRepository.findByProjectRequirementId(requirement.getId())
                .map(LockedIdea::getProjectId)
                .orElse(null);
        return new ProjectRequirementView(requirement.getId(), requirement.getTenantType(), requirement.getLockedIdeaId(),
                requirement.getBrandContextId(), requirement.getBriefText(), requirement.getTargetAudience(), requirement.getCampaignDirection(),
                requirement.getDurationSeconds(), splitLanguages(requirement.getLanguages()),
                requirement.getQuotedPlatformCost(), requirement.getQuotedCreatorMarginPercent(),
                requirement.getQuotedTotalPrice(), requirement.getQuotedCurrency(),
                requirement.getRequiredPaymentPercent(), requirement.getRequiredAmount(),
                requirement.getShareToken(), requirement.getShareTokenExpiresAt(),
                requirement.isFunded(), requirement.getFundedBy(), lockedProjectId, requirement.getCreatedAt(),
                requirement.getClientUpdatedAt());
    }

    /** The base fields only -- brand/product/reference-images are assembled by {@code
     * PublicProjectRequirementController} itself (see {@link #identifyByShareToken}), since they
     * live in services this class has no reason to depend on. */
    private PublicProjectRequirementView toPublicView(ProjectRequirement requirement) {
        return new PublicProjectRequirementView(requirement.getBriefText(), requirement.getTargetAudience(),
                requirement.getCampaignDirection(), requirement.getDurationSeconds(), splitLanguages(requirement.getLanguages()),
                requirement.getQuotedTotalPrice(), requirement.getQuotedCurrency(),
                requirement.getRequiredPaymentPercent(), requirement.getRequiredAmount(), requirement.isFunded(),
                null, null, List.of(), List.of());
    }

    private static List<String> splitLanguages(String languages) {
        if (languages == null || languages.isBlank()) {
            return List.of();
        }
        return Arrays.stream(languages.split(","))
                .map(String::trim)
                .filter(language -> !language.isBlank())
                .collect(Collectors.toList());
    }
}
