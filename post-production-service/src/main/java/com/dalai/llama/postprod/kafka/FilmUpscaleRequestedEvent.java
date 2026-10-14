package com.dalai.llama.postprod.kafka;

import java.util.UUID;

/** An upscaled copy of a film was asked for; the consumer runs Topaz on it. */
public record FilmUpscaleRequestedEvent(UUID renderId, String tenantId, UUID projectId) {
}
