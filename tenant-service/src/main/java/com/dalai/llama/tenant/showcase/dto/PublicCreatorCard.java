package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;

import java.util.Set;

/** A creator in the public catalogue: who they are, what they make, and their latest film's
 * picture as the card's cover. */
public record PublicCreatorCard(
        String handle,
        String displayName,
        String headline,
        String avatarUrl,
        Set<ShowcaseIndustry> industries,
        int filmCount,
        int followerCount,
        String coverUrl,
        String profileUrl
) {
}
