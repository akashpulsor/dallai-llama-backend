package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;

import java.util.UUID;

/** One of the creator's own showcase items, as they manage it. */
public record MyShowcaseItemView(
        UUID id,
        String publicId,
        String youtubeVideoId,
        String title,
        String thumbnailUrl,
        int aspectW,
        int aspectH,
        ShowcaseOrigin origin,
        ShowcaseIndustry industry,
        ShowcaseFormat format,
        String clientLabel,
        String titleOverride,
        ShowcaseItemStatus status,
        boolean hiddenByOps,
        int playCount,
        int fullPlayCount,
        String watchUrl
) {
}
