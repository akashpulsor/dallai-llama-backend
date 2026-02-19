package com.dalai.llama.tenant.dto.request;

import lombok.Builder;

import java.util.UUID;

/**
 * Channel allocation for rate limiting
 */
@Builder
public record ChannelData(
        UUID id,
        String direction,        // INBOUND, OUTBOUND, BOTH
        int total,
        Integer inbound,
        Integer outbound
) {}
