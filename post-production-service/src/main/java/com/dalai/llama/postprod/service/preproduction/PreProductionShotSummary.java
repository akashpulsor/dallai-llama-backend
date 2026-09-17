package com.dalai.llama.postprod.service.preproduction;

import java.util.UUID;

/**
 * A shot's identity and where it sits in the film.
 *
 * <p>Structural copy of pre-production-service's own {@code ShotView}, narrowed to the four fields
 * assembling a film needs. The two services share no module, so the field NAMES have to match the
 * wire exactly -- a rename on one side and not the other deserialises silently to null, and a null
 * shotNumber here means the film comes out in an arbitrary order.
 */
public record PreProductionShotSummary(UUID id, String shotRef, Integer shotNumber, Integer durationSeconds) {
}
