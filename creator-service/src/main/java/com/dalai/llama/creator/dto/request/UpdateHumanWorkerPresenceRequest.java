package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateHumanWorkerPresenceRequest(
        @Size(max = 40) String role,
        Boolean online,
        @Size(max = 160) String displayName
) {
}
