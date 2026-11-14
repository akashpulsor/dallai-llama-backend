package com.dalai.llama.postprod.kafka;

import java.util.UUID;

/** A film waiting for its Dalaillama-stamped copy; everything else lives on its row. A published
 * film carries {@code renderId} (film_render); a creator's uploaded film carries
 * {@code uploadStampId} (upload_stamp) and no render or project. Both go through one consumer, so
 * ffmpeg runs one stamp at a time. */
public record FilmStampRequestedEvent(UUID renderId, UUID tenantId, UUID projectId, UUID uploadStampId) {

    public static FilmStampRequestedEvent film(UUID renderId, UUID tenantId, UUID projectId) {
        return new FilmStampRequestedEvent(renderId, tenantId, projectId, null);
    }

    public static FilmStampRequestedEvent upload(UUID uploadStampId, UUID tenantId) {
        return new FilmStampRequestedEvent(null, tenantId, null, uploadStampId);
    }

    /** The Kafka key: the project for a film, the stamp request for an upload. */
    public String key() {
        return (projectId != null ? projectId : uploadStampId).toString();
    }
}
