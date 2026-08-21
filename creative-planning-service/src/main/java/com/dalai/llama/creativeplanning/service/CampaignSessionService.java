package com.dalai.llama.creativeplanning.service;

import com.dalai.llama.creativeplanning.domain.CampaignSessionStatus;
import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningSession;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import com.dalai.llama.creativeplanning.dto.CampaignSessionView;
import com.dalai.llama.creativeplanning.dto.CreateCampaignSessionRequest;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CampaignSessionService {

    private final CampaignPlanningSessionRepository campaignPlanningSessionRepository;
    private final BrandContextService brandContextService;
    private final ProductProfileService productProfileService;

    public CampaignSessionService(
            CampaignPlanningSessionRepository campaignPlanningSessionRepository,
            BrandContextService brandContextService,
            ProductProfileService productProfileService
    ) {
        this.campaignPlanningSessionRepository = campaignPlanningSessionRepository;
        this.brandContextService = brandContextService;
        this.productProfileService = productProfileService;
    }

    @Transactional
    public CampaignSessionView create(UUID tenantId, CreateCampaignSessionRequest request) {
        BrandContext brand = brandContextService.requireBrand(tenantId);
        if (request.productProfileId() != null) {
            productProfileService.requireProduct(tenantId, request.productProfileId());
        }
        OffsetDateTime now = OffsetDateTime.now();
        CampaignPlanningSession session = campaignPlanningSessionRepository.save(CampaignPlanningSession.builder()
                .tenantId(tenantId)
                .brandContextId(brand.getId())
                .productProfileId(request.productProfileId())
                .budgetTier(request.budgetTier())
                .status(CampaignSessionStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toView(session);
    }

    @Transactional(readOnly = true)
    public CampaignSessionView get(UUID tenantId, UUID sessionId) {
        return toView(requireSession(tenantId, sessionId));
    }

    @Transactional(readOnly = true)
    public List<CampaignSessionView> list(UUID tenantId) {
        return campaignPlanningSessionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    CampaignPlanningSession requireSession(UUID tenantId, UUID sessionId) {
        return campaignPlanningSessionRepository.findByIdAndTenantId(sessionId, tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No campaign planning session " + sessionId));
    }

    void markLocked(CampaignPlanningSession session) {
        session.setStatus(CampaignSessionStatus.LOCKED);
        session.setUpdatedAt(OffsetDateTime.now());
        campaignPlanningSessionRepository.save(session);
    }

    private CampaignSessionView toView(CampaignPlanningSession session) {
        return new CampaignSessionView(session.getId(), session.getBrandContextId(), session.getProductProfileId(),
                session.getBudgetTier(), session.getStatus(), session.getCreatedAt());
    }
}
