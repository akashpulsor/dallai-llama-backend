package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.youtube.domain.ChannelStatus;
import com.dalai.llama.tenant.youtube.domain.entity.CreatorYouTubeChannel;

/** The picture shown for a creator: their own avatar, or their verified channel's picture until
 * they upload one. */
final class CreatorAvatar {

    private CreatorAvatar() {
    }

    static String of(CreatorPublicProfile profile, CreatorYouTubeChannel channel) {
        if (profile.getAvatarUrl() != null) return profile.getAvatarUrl();
        return channel != null && channel.getStatus() == ChannelStatus.VERIFIED ? channel.getChannelThumbnailUrl() : null;
    }
}
