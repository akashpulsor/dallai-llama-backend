package com.dalai.llama.tenant.dto.request;

import com.dalai.llama.tenant.domain.entity.enums.DataRegion;
import jakarta.validation.constraints.Min;

import java.time.LocalTime;

public record UpdateCompliancePolicyRequest(

        Boolean consentPromptRequired,

        @Min(1)
        Integer retentionDays,

        DataRegion dataRegion,

        Boolean piiPauseRequired,
        Boolean dncCheckRequired,
        Boolean callTimeRestrictionEnabled,

        LocalTime callWindowStart,
        LocalTime callWindowEnd,

        String blockedDays
) {}
