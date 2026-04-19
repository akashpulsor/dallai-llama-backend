package com.dalai.llama.tenant.dto.response;

import lombok.Builder;

import java.util.UUID;

@Builder
public record AppInfo(
        UUID id,
        String appType,
        String displayName,
        String url,
        String icon
) {}

