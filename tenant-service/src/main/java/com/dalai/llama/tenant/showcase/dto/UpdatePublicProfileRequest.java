package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** Full replacement of the creator-editable profile fields (PUT semantics). */
public record UpdatePublicProfileRequest(
        @NotBlank @Size(max = 40) String handle,
        @NotBlank @Size(max = 80) String displayName,
        @Size(max = 120) String headline,
        @Size(max = 1000) String bio,
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter country code") String countryCode,
        @Size(max = 255) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) link") String websiteUrl,
        @NotNull Set<ShowcaseIndustry> industries,
        boolean autoPicksEnabled
) {
}
