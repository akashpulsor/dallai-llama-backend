package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.ProfileChecklistItem;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** The creator's own view of their public profile, including what is still missing. */
public record MyPublicProfileView(
        String handle,
        String publicUrl,
        String displayName,
        String headline,
        String bio,
        String avatarUrl,
        String countryCode,
        String websiteUrl,
        Set<ShowcaseIndustry> industries,
        boolean autoPicksEnabled,
        ProfileStatus status,
        /* Null when the handle can be changed now. */
        OffsetDateTime handleChangeAllowedFrom,
        List<ProfileChecklistItem> missing
) {
}
