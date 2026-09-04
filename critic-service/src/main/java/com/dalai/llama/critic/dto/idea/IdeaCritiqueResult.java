package com.dalai.llama.critic.dto.idea;

import java.util.List;
import java.util.UUID;

/** {@code sessionId} keys the step-by-step reasoning log ({@code CritiqueThoughtService}) --
 * fetchable via the same {@code GET /v1/critiques/{sessionId}/thoughts} every other critique flow
 * already exposes (tenant-authenticated, not yet wired through creative-planning-service or the
 * frontend), for a creator who wants the full trace rather than just the per-idea
 * strengths/concerns. */
public record IdeaCritiqueResult(
        UUID sessionId,
        List<IdeaCritiqueItem> items
) {
}
