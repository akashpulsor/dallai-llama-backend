package com.dalai.llama.postprod.kafka;

import java.util.UUID;

/** A published film waiting for its Dalaillama-stamped copy; everything else lives on the film_render row. */
public record FilmStampRequestedEvent(UUID renderId, UUID tenantId, UUID projectId) {
}
