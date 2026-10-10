package com.dalai.llama.tenant.youtube.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Whatever the creator pasted: a channel link, an @handle or a channel id. */
public record LinkChannelRequest(@NotBlank @Size(max = 300) String channel) {
}
