package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;

import java.math.BigDecimal;

/**
 * A video file for the profile, with what the browser read from it (length and size, from the
 * video element) and what the creator said about it.
 *
 * @param rightsConfirmed the creator confirms the video is theirs to show
 */
public record UploadShowcaseFilmRequest(
        String title,
        BigDecimal durationSeconds,
        Integer width,
        Integer height,
        ShowcaseIndustry industry,
        ShowcaseFormat format,
        /* How the client is described; the client's name stays hidden unless written here. */
        String clientLabel,
        boolean rightsConfirmed
) {
}
