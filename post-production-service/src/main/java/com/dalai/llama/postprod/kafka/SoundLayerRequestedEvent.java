package com.dalai.llama.postprod.kafka;

import java.util.UUID;

/**
 * A sound layer waiting to be prepared. Carries the id and the identity needed to rebuild a
 * TenantContext; everything else (prompt, kind, uploaded object) lives on the sound_layer row.
 */
public record SoundLayerRequestedEvent(UUID layerId, UUID tenantId, UUID projectId, UUID userId) {
}
