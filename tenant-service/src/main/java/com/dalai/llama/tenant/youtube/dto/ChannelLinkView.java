package com.dalai.llama.tenant.youtube.dto;

import com.dalai.llama.tenant.youtube.domain.ChannelStatus;

import java.time.OffsetDateTime;

/** The creator's linked channel, or the one waiting for verification. */
public record ChannelLinkView(
        ChannelStatus status,
        String channelId,
        String channelTitle,
        String channelThumbnailUrl,
        String channelUrl,
        /* The code to put in the channel description; null once verified. */
        String verificationCode,
        OffsetDateTime verifiedAt,
        OffsetDateTime lastSyncedAt
) {
}
