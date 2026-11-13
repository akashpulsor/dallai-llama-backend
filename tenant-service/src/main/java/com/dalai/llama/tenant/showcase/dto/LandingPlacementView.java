package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A creator's film as the ops page lists it for the landing page: what it is, how it does, and
 * where ops placed it. */
public record LandingPlacementView(
        String publicId,
        String title,
        String thumbnailUrl,
        String watchUrl,
        ShowcaseIndustry industry,
        String creatorHandle,
        String creatorName,
        int likeCount,
        int playCount,
        BigDecimal globalScore,
        boolean landingHidden,
        OffsetDateTime landingHeroAt
) {
    public static LandingPlacementView of(ShowcaseItem item, PublicShowcaseCard card) {
        return new LandingPlacementView(item.getPublicId(), card.title(), card.thumbnailUrl(), card.watchUrl(),
                item.getIndustry(), card.creator().handle(), card.creator().displayName(), item.getLikeCount(),
                item.getPlayCount(), item.getGlobalScore(), item.isLandingHidden(), item.getLandingHeroAt());
    }
}
