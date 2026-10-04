package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.dto.PublicProjectRequirementView;
import com.dalai.llama.creativeplanning.dto.UpdateRequirementQuoteRequest;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningSessionRepository;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementRepository;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The client is asked what they want to spend, not shown a price computed from the per-second
 * rate; the creator sets the price. A brief can be funded only once that price exists.
 */
class ClientBudgetTest {

    private final ProjectRequirementRepository requirements = mock(ProjectRequirementRepository.class);
    private final BillingServiceClient billing = mock(BillingServiceClient.class);
    private final ProjectRequirementService service = new ProjectRequirementService(requirements, mock(LockedIdeaRepository.class),
            mock(CampaignPlanningSessionRepository.class), mock(BrandContextService.class), billing,
            mock(RequirementFundingBillingClient.class), mock(ReferenceMaterialAnalysisService.class), 7, "");

    private ProjectRequirement brief;

    @BeforeEach
    void setUp() {
        brief = ProjectRequirement.builder().id(UUID.randomUUID()).tenantId(UUID.randomUUID()).shareToken("tok")
                .shareTokenExpiresAt(OffsetDateTime.now().plusDays(3)).durationSeconds(60).requiredPaymentPercent(100)
                .funded(false).build();
        when(requirements.findByShareToken("tok")).thenReturn(Optional.of(brief));
        when(requirements.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void theClientsBudgetIsRecordedAndNoPriceIsComputedForThem() {
        PublicProjectRequirementView view = service.updateFromClient("tok", null, null, null, null, null, null, new BigDecimal("1300"));

        assertThat(view.clientBudget()).isEqualByComparingTo("1300.00");
        assertThat(view.quotedTotalPrice()).isNull();
        verify(billing, never()).quoteVideoPrice(any(), anyInt());
    }

    @Test
    void changingTheLengthNoLongerRepricesTheBrief() {
        brief.setQuotedTotalPrice(new BigDecimal("1299.00"));

        service.updateFromClient("tok", null, null, null, null, null, 30, null);

        assertThat(brief.getDurationSeconds()).isEqualTo(30);
        assertThat(brief.getQuotedTotalPrice()).isEqualByComparingTo("1299.00");
        verify(billing, never()).quoteVideoPrice(any(), anyInt());
    }

    @Test
    void aBudgetOfZeroIsRefused() {
        assertThatThrownBy(() -> service.updateFromClient("tok", null, null, null, null, null, null, BigDecimal.ZERO))
                .isInstanceOf(CreativePlanningException.class);
    }

    @Test
    void theCreatorPricesABriefThatHadNoPriceAndTheClientCanThenSeeIt() {
        when(requirements.findByIdAndTenantId(brief.getId(), brief.getTenantId())).thenReturn(Optional.of(brief));
        service.updateFromClient("tok", null, null, null, null, null, null, new BigDecimal("1300"));

        service.updateQuote(brief.getTenantId(), brief.getId(), new UpdateRequirementQuoteRequest(new BigDecimal("1500"), 100));

        PublicProjectRequirementView seen = service.getByShareToken("tok");
        assertThat(seen.quotedTotalPrice()).isEqualByComparingTo("1500");
        assertThat(seen.quotedCurrency()).isEqualTo("INR");
        assertThat(seen.clientBudget()).isEqualByComparingTo("1300");
    }
}
