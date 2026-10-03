package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.dto.ProjectQuoteView;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningSessionRepository;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementRepository;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectRequirementServiceProjectQuoteTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID requirementId = UUID.randomUUID();
    private final LockedIdeaRepository lockedIdeas = mock(LockedIdeaRepository.class);
    private final ProjectRequirementRepository requirements = mock(ProjectRequirementRepository.class);
    private final ProjectRequirementService service = new ProjectRequirementService(requirements, lockedIdeas,
            mock(CampaignPlanningSessionRepository.class), mock(BrandContextService.class), mock(BillingServiceClient.class),
            mock(RequirementFundingBillingClient.class), mock(ReferenceMaterialAnalysisService.class), 7, "");

    @Test
    void carriesTheRawQuoteAndUpfrontTermsBillingNeedsForTheLockBalance() {
        lockedIdeaFor(tenantId);
        when(requirements.findById(requirementId)).thenReturn(Optional.of(ProjectRequirement.builder()
                .id(requirementId).tenantId(tenantId)
                .quotedTotalPrice(new BigDecimal("3450.00")).quotedCreatorMarginPercent(new BigDecimal("15"))
                .quotedCurrency("INR").requiredPaymentPercent(25).funded(true)
                .build()));

        ProjectQuoteView quote = service.findProjectQuote(tenantId, projectId).orElseThrow();

        assertThat(quote.quotedTotalPrice()).isEqualByComparingTo("3450.00");
        assertThat(quote.requiredAmount()).isEqualByComparingTo("862.50");
        assertThat(quote.funded()).isTrue();
        assertThat(quote.quotedCurrency()).isEqualTo("INR");
    }

    @Test
    void noQuoteForAnotherTenantsProjectOrAnUnquotedRequirement() {
        lockedIdeaFor(UUID.randomUUID());
        assertThat(service.findProjectQuote(tenantId, projectId)).isEmpty();

        lockedIdeaFor(tenantId);
        when(requirements.findById(requirementId)).thenReturn(Optional.of(ProjectRequirement.builder()
                .id(requirementId).tenantId(tenantId).build()));
        assertThat(service.findProjectQuote(tenantId, projectId)).isEmpty();
    }

    private void lockedIdeaFor(UUID ownerTenantId) {
        when(lockedIdeas.findTopByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(Optional.of(LockedIdea.builder()
                .tenantId(ownerTenantId).projectId(projectId).projectRequirementId(requirementId).build()));
    }
}
