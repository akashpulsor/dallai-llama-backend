package com.dalai.llama.tenant.showcase.dto;

import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Full replacement of an item's editable fields; {@code visible=false} hides it. */
public record UpdateShowcaseItemRequest(
        @NotNull ShowcaseIndustry industry,
        @NotNull ShowcaseFormat format,
        @Size(max = 80) String clientLabel,
        @Size(max = 120) String titleOverride,
        boolean visible
) {
}
