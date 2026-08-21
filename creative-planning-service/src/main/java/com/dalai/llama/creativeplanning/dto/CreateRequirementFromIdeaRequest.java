package com.dalai.llama.creativeplanning.dto;

import com.dalai.llama.creativeplanning.domain.TenantType;
import jakarta.validation.constraints.NotNull;

/** Entry point A: the brand went through the full campaign-planning chat and already has a
 * {@code LockedIdea} -- the requirement's brief/audience/angle are populated from it. */
public record CreateRequirementFromIdeaRequest(
        @NotNull TenantType tenantType
) {
}
