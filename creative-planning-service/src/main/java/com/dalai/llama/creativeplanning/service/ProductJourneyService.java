package com.dalai.llama.creativeplanning.service;

import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningSession;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import com.dalai.llama.creativeplanning.dto.CampaignSessionSummaryView;
import com.dalai.llama.creativeplanning.dto.LockedIdeaView;
import com.dalai.llama.creativeplanning.dto.ProductJourneyView;
import com.dalai.llama.creativeplanning.dto.ProductProfileView;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningMessageRepository;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningSessionRepository;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure aggregation over data every other service in this module already owns -- no new state of
 * its own. Closes the design doc's {@code GET /v1/products/{productId}/journey}: "what has
 * happened to this product," start to finish.
 */
@Service
public class ProductJourneyService {

    private final ProductProfileService productProfileService;
    private final ProductReferenceImageService productReferenceImageService;
    private final CampaignPlanningSessionRepository campaignPlanningSessionRepository;
    private final CampaignPlanningMessageRepository campaignPlanningMessageRepository;
    private final LockedIdeaRepository lockedIdeaRepository;

    public ProductJourneyService(
            ProductProfileService productProfileService,
            ProductReferenceImageService productReferenceImageService,
            CampaignPlanningSessionRepository campaignPlanningSessionRepository,
            CampaignPlanningMessageRepository campaignPlanningMessageRepository,
            LockedIdeaRepository lockedIdeaRepository
    ) {
        this.productProfileService = productProfileService;
        this.productReferenceImageService = productReferenceImageService;
        this.campaignPlanningSessionRepository = campaignPlanningSessionRepository;
        this.campaignPlanningMessageRepository = campaignPlanningMessageRepository;
        this.lockedIdeaRepository = lockedIdeaRepository;
    }

    @Transactional(readOnly = true)
    public ProductJourneyView journey(UUID tenantId, UUID productId) {
        ProductProfile product = productProfileService.requireProduct(tenantId, productId);
        ProductProfileView productView = new ProductProfileView(product.getId(), product.getBrandContextId(),
                product.getName(), product.getDescription(), product.getCategory());

        var referenceImages = productReferenceImageService.list(tenantId, productId);

        var sessions = campaignPlanningSessionRepository.findByProductProfileIdOrderByCreatedAtDesc(productId).stream()
                .map(this::toSessionSummary)
                .collect(Collectors.toList());

        return new ProductJourneyView(productView, referenceImages, sessions);
    }

    private CampaignSessionSummaryView toSessionSummary(CampaignPlanningSession session) {
        LockedIdeaView lockedIdea = lockedIdeaRepository.findBySessionId(session.getId())
                .map(idea -> new LockedIdeaView(idea.getId(), idea.getSessionId(), idea.getTitle(), idea.getConcept(),
                        idea.getTargetAudience(), idea.getCampaignAngle(), idea.getKeyMessage(), idea.getTone(),
                        idea.getBudgetTier(), idea.getCreatedAt()))
                .orElse(null);
        int messageCount = (int) campaignPlanningMessageRepository.countBySessionId(session.getId());
        return new CampaignSessionSummaryView(session.getId(), session.getStatus(), session.getBudgetTier(),
                messageCount, lockedIdea, session.getCreatedAt());
    }
}
