package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;

import java.util.List;
import java.util.Set;

/** A creator's public page. {@code ready=false} means the creator hasn't linked a channel and
 * picked their videos yet: the page shows "coming soon" and search engines are told not to
 * index it. */
public record PublicCreatorProfileView(
        String handle,
        String displayName,
        String headline,
        String bio,
        String avatarUrl,
        String countryCode,
        String websiteUrl,
        Set<ShowcaseIndustry> industries,
        String youtubeChannelUrl,
        int followerCount,
        boolean ready,
        List<PublicShowcaseCard> items
) {
}
