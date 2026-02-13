package com.dalai.llama.tenant.dto.response;

import com.dalai.llama.tenant.domain.entity.enums.DataRegion;
import java.time.LocalTime;
import java.util.List;

public record CompliancePolicyResponse(

        boolean consentPromptRequired,
        int retentionDays,
        DataRegion dataRegion,
        boolean piiPauseRequired,
        boolean dncCheckRequired,
        boolean callTimeRestrictionEnabled,
        LocalTime callWindowStart,
        LocalTime callWindowEnd,
        List<String> blockedDays
) {}
