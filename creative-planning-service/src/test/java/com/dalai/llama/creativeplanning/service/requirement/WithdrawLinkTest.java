package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.repository.CampaignPlanningSessionRepository;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementRepository;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An expired request's brief: its link stops working, unless the brand already paid. */
class WithdrawLinkTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID requirementId = UUID.randomUUID();
    private final ProjectRequirementRepository requirements = mock(ProjectRequirementRepository.class);
    private final ProjectRequirementService service = new ProjectRequirementService(requirements, mock(LockedIdeaRepository.class),
            mock(CampaignPlanningSessionRepository.class), mock(BrandContextService.class), mock(BillingServiceClient.class),
            mock(RequirementFundingBillingClient.class), mock(ReferenceMaterialAnalysisService.class), 7, "");

    @Test
    void anUnpaidBriefsLinkStopsWorkingNow() {
        ProjectRequirement brief = brief(false);
        service.withdrawLink(tenantId, requirementId);
        assertThat(brief.getShareTokenExpiresAt()).isBeforeOrEqualTo(OffsetDateTime.now());
        verify(requirements).save(brief);
    }

    @Test
    void aPaidBriefKeepsItsLink() {
        ProjectRequirement brief = brief(true);
        OffsetDateTime until = brief.getShareTokenExpiresAt();
        assertThatThrownBy(() -> service.withdrawLink(tenantId, requirementId)).isInstanceOf(CreativePlanningException.class)
                .hasMessageContaining("already paid");
        assertThat(brief.getShareTokenExpiresAt()).isEqualTo(until);
        verify(requirements, never()).save(any());
    }

    private ProjectRequirement brief(boolean funded) {
        ProjectRequirement brief = ProjectRequirement.builder().id(requirementId).tenantId(tenantId).funded(funded)
                .shareToken("tok").shareTokenExpiresAt(OffsetDateTime.now().plusDays(7)).build();
        when(requirements.findByIdAndTenantId(requirementId, tenantId)).thenReturn(Optional.of(brief));
        return brief;
    }
}
