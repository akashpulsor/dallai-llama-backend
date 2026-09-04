package com.dalai.llama.creativeplanning.service.requirement.critic;

import java.util.List;
import java.util.UUID;

/** Wire-contract mirror of critic-service's own {@code IdeaCritiqueResult}. */
public record IdeaCritiqueResult(
        UUID sessionId,
        List<IdeaCritiqueItem> items
) {
}
