package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.dto.ProjectQuoteView;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningSessionRepository;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementRepository;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.TenantType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectRequirementServiceProjectQuoteTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID requirementId = UUID.randomUUID();
    private final LockedIdeaRepository lockedIdeas = mock(LockedIdeaRepository.class);
    private final ProjectRequirementRepository requirements = mock(ProjectRequirementRepository.class);
    private final BillingServiceClient billing = mock(BillingServiceClient.class);
    private final ProjectRequirementService service = new ProjectRequirementService(requirements, lockedIdeas,
            mock(CampaignPlanningSessionRepository.class), mock(BrandContextService.class), billing,
            mock(RequirementFundingBillingClient.class), mock(ReferenceMaterialAnalysisService.class), 7, "");

    @Test
    void carriesTheRawQuoteAndUpfrontTermsBillingNeedsForTheLockBalance() {
        lockedIdeaFor(tenantId);
        when(requirements.findById(requirementId)).thenReturn(Optional.of(ProjectRequirement.builder()
                .id(requirementId).tenantId(tenantId)
                .durationSeconds(30).quotedPlatformCost(new BigDecimal("3000.00"))
                .quotedTotalPrice(new BigDecimal("3450.00")).quotedCreatorMarginPercent(new BigDecimal("15"))
                .quotedCurrency("INR").requiredPaymentPercent(25).funded(true)
                .build()));

        ProjectQuoteView quote = service.findProjectQuote(tenantId, projectId).orElseThrow();

        assertThat(quote.durationSeconds()).isEqualTo(30);
        assertThat(quote.quotedPlatformCost()).isEqualByComparingTo("3000.00");
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

    @Test
    void theNextBriefIsPrefilledFromTheLockedOneAndQuotedAfresh() {
        lockedIdeaFor(tenantId);
        UUID brand = UUID.randomUUID();
        when(requirements.findById(requirementId)).thenReturn(Optional.of(ProjectRequirement.builder()
                .id(requirementId).tenantId(tenantId).tenantType(TenantType.AI_VIDEO_CREATOR).brandContextId(brand)
                .briefText("City Professional launch film").targetAudience("Young professionals")
                .durationSeconds(60).languages("English,Hindi").budgetTier(BudgetTier.PREMIUM).build()));
        when(requirements.findByPreviousRequirementId(requirementId)).thenReturn(Optional.empty());
        when(billing.quoteVideoPrice(tenantId, 60)).thenReturn(new BillingServiceClient.VideoPriceQuote(60, 15,
                null, null, null, null, null, new BigDecimal("1047.00"), new BigDecimal("24.09"), null,
                new BigDecimal("1299.00"), "INR"));
        when(requirements.save(any(ProjectRequirement.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(service.startNextBrief(tenantId, projectId).shareToken()).isNotBlank();

        ArgumentCaptor<ProjectRequirement> saved = ArgumentCaptor.forClass(ProjectRequirement.class);
        verify(requirements).save(saved.capture());
        ProjectRequirement next = saved.getValue();
        assertThat(next.getPreviousRequirementId()).isEqualTo(requirementId);
        assertThat(next.getBrandContextId()).isEqualTo(brand);
        assertThat(next.getLanguages()).isEqualTo("English,Hindi");
        assertThat(next.getQuotedTotalPrice()).isEqualByComparingTo("1299.00");
        assertThat(next.isFunded()).isFalse();
    }

    @Test
    void aRepeatReturnsTheBriefAlreadyStarted() {
        lockedIdeaFor(tenantId);
        when(requirements.findById(requirementId)).thenReturn(Optional.of(ProjectRequirement.builder()
                .id(requirementId).tenantId(tenantId).build()));
        when(requirements.findByPreviousRequirementId(requirementId)).thenReturn(Optional.of(ProjectRequirement.builder()
                .shareToken("existing").shareTokenExpiresAt(OffsetDateTime.now().plusDays(3)).build()));

        assertThat(service.startNextBrief(tenantId, projectId).shareToken()).isEqualTo("existing");
        verify(requirements, never()).save(any());
    }

    private void lockedIdeaFor(UUID ownerTenantId) {
        when(lockedIdeas.findTopByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(Optional.of(LockedIdea.builder()
                .tenantId(ownerTenantId).projectId(projectId).projectRequirementId(requirementId).build()));
    }
}
