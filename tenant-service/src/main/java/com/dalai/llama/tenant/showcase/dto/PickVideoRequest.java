package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Put one of the creator's own YouTube videos on their profile. */
public record PickVideoRequest(
        @NotBlank @Size(max = 16) String youtubeVideoId,
        @NotNull ShowcaseIndustry industry,
        @NotNull ShowcaseFormat format,
        @Size(max = 80) String clientLabel,
        @Size(max = 120) String titleOverride,
        @AssertTrue(message = "confirm you may show this video publicly") boolean rightsConfirmed
) {
}
