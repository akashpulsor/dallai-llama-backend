package com.dalai.llama.postprod.kafka;

import java.util.UUID;

/**
 * Published when a film assembly is accepted, consumed by this same service's worker side.
 *
 * <p>Carries the render id and the identity needed to rebuild a TenantContext, and nothing else.
 * Everything about the request lives on the film_render row and on the shots' current cuts, so a
 * replayed or late event assembles the film as it stands NOW rather than from a stale copy of what
 * it looked like when the button was pressed -- which is the behaviour you want, since the only
 * reason a creator re-assembles is that something changed.
 */
public record FilmAssemblyRequestedEvent(UUID renderId, String tenantId, UUID projectId, UUID userId) {
}
