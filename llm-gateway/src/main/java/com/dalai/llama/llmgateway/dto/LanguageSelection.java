package com.dalai.llama.llmgateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Platform-neutral language selected by a client. Provider language codes never enter this DTO. */
public record LanguageSelection(
        @NotBlank @Size(max = 3)
        @Pattern(regexp = "^[A-Za-z]{2,3}$", message = "language.code must be an ISO 639 code")
        String code,
        @Size(max = 4)
        @Pattern(regexp = "^[A-Za-z]{4}$", message = "language.script must be an ISO 15924 code")
        String script,
        @Size(max = 3)
        @Pattern(regexp = "^(?:[A-Za-z]{2}|[0-9]{3})$", message = "language.region must be an ISO country or M.49 code")
        String region
) {
}
