package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.domain.ShowcaseOrigin;
import com.dalai.llama.tenant.showcase.domain.VideoHostType;

import java.math.BigDecimal;

/** A showcase video as anyone on the internet sees it. Carries no internal ids
 * (CREATOR_SHOWCASE.md rule 19): only the public id, the YouTube id and the creator's handle. */
public record PublicShowcaseCard(
        String publicId,
        String youtubeVideoId,
        String title,
        String thumbnailUrl,
        int aspectW,
        int aspectH,
        boolean vertical,
        BigDecimal durationSeconds,
        ShowcaseOrigin origin,
        ShowcaseIndustry industry,
        ShowcaseFormat format,
        String clientLabel,
        String watchUrl,
        /* YOUTUBE: embed youtubeVideoId. SELF: fetch GET /public/showcase/{publicId}/source for a
         * short-lived MP4 link and play it in a plain video element. */
        VideoHostType host,
        /* Likes on Dalai Llama (ours, never YouTube's). */
        int likeCount,
        Creator creator
) {
    public record Creator(String handle, String displayName, String avatarUrl, String profileUrl) {
    }
}
