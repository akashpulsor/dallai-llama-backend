package com.dalai.llama.tenant.dto.response;

import java.util.UUID;

public record AppInfo(
        UUID id,
        String appType,
        String displayName,
        String url,
        String icon
) {}

