package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.TenantType;
import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.dto.CreateStandaloneRequirementRequest;
import com.dalai.llama.creativeplanning.dto.CreateStandaloneRequirementRequest.Pricing;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningSessionRepository;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementRepository;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A brand's request becomes a brief that is priced at creation, so the brand can pay at once. */
class StandaloneRequirementPricingTest {

    /** Rs 4,999 for 60 seconds. */
    private static final BigDecimal PER_SECOND = new BigDecimal("4999").divide(BigDecimal.valueOf(60), 4, java.math.RoundingMode.HALF_UP);
    private final UUID tenantId = UUID.randomUUID();
    private final ProjectRequirementRepository requirements = mock(ProjectRequirementRepository.class);
    private final BillingServiceClient billing = mock(BillingServiceClient.class);
    private final ProjectRequirementService service = new ProjectRequirementService(requirements, mock(LockedIdeaRepository.class),
            mock(CampaignPlanningSessionRepository.class), mock(BrandContextService.class), billing,
            mock(RequirementFundingBillingClient.class), mock(ReferenceMaterialAnalysisService.class), 7, "");

    @BeforeEach
    void setUp() {
        when(requirements.save(any())).thenAnswer(i -> i.getArgument(0));
        when(billing.quoteVideoPrice(tenantId, 30)).thenReturn(new BillingServiceClient.VideoPriceQuote(30, 4, null, null, null, null, null,
                new BigDecimal("2000.00"), new BigDecimal("50"), new BigDecimal("1000.00"), new BigDecimal("3000.00"), "INR"));
    }

    @Test
    void aRate_pricesTheLength_roundedUp_andTheCreatorsMarginFollowsFromTheRealCost() {
        // Rs 4,999 a minute: 30 seconds is Rs 2,499.50, charged as Rs 2,500; 60 seconds stays Rs 4,999.
        ProjectRequirement saved = create(new Pricing(PER_SECOND, 30));

        assertThat(saved.getQuotedTotalPrice()).isEqualByComparingTo("2500");
        assertThat(saved.getPricePerSecond()).isEqualByComparingTo(PER_SECOND);
        assertThat(saved.getQuotedPlatformCost()).isEqualByComparingTo("2000.00");
        assertThat(saved.getQuotedCreatorMarginPercent()).isEqualByComparingTo("25.00");
        assertThat(saved.getQuotedCurrency()).isEqualTo("INR");
        assertThat(saved.getRequiredPaymentPercent()).isEqualTo(30);
        assertThat(saved.getRequiredAmount()).isEqualByComparingTo("750.00");
        assertThat(saved.isFunded()).isFalse();
    }

    @Test
    void aBriefPricedByLength_isRePricedWhenTheBrandChangesTheLength_andPreviewsAtTheSameRate() {
        ProjectRequirement saved = create(new Pricing(PER_SECOND, 30));
        saved.setShareToken("tok");
        saved.setShareTokenExpiresAt(java.time.OffsetDateTime.now().plusDays(1));
        when(requirements.findByShareToken("tok")).thenReturn(java.util.Optional.of(saved));

        assertThat(service.previewQuoteByShareToken("tok", 60).totalPrice()).isEqualTo("4999");
        service.updateFromClient("tok", null, null, null, null, null, 60, null);

        assertThat(saved.getQuotedTotalPrice()).isEqualByComparingTo("4999");
        assertThat(saved.getRequiredAmount()).isEqualByComparingTo("1499.70");
    }

    @Test
    void noFixedPrice_isTheCreatorsOwnRateForTheLength() {
        ProjectRequirement saved = create(new Pricing(null, 40));

        assertThat(saved.getQuotedTotalPrice()).isEqualByComparingTo("3000.00");
        assertThat(saved.getQuotedCreatorMarginPercent()).isEqualByComparingTo("50.00");
        assertThat(saved.getRequiredAmount()).isEqualByComparingTo("1200.00");
    }

    @Test
    void withoutPricing_theBriefStartsUnpriced_forTheCreatorToPrice() {
        ProjectRequirement saved = create(null);

        assertThat(saved.getQuotedTotalPrice()).isNull();
        assertThat(saved.getRequiredAmount()).isNull();
        verify(billing, never()).quoteVideoPrice(any(), anyInt());
    }

    private ProjectRequirement create(Pricing pricing) {
        service.createStandalone(tenantId, null, new CreateStandaloneRequirementRequest("A 30s reel for our mango pickle", null, null, 30,
                List.of("Hindi"), TenantType.AI_VIDEO_CREATOR, null, null, null, pricing));
        ArgumentCaptor<ProjectRequirement> saved = ArgumentCaptor.forClass(ProjectRequirement.class);
        verify(requirements).save(saved.capture());
        return saved.getValue();
    }
}
