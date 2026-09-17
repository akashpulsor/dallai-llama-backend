package com.dalai.llama.postprod.service.preproduction;

import java.util.UUID;

/** The one and only way DialogueSyncCoordinator learns what a shot's dialogue actually is --
 * pre-production-service (a separate, not-yet-built service with its own DB) is the sole source
 * of truth for this. Behind an interface so the real implementation can land later without
 * DialogueSyncCoordinator changing at all. */
public interface PreProductionClient {

    /** Every shot in the project, for putting a film together in the order it was written.
     *
     * <p>Ordering comes from here rather than from post-production's own rows: shot_number is
     * pre-production's fact about the edit, and a film assembled from any other ordering -- the
     * order cuts happened to be made in, say -- is not the film. */
    java.util.List<PreProductionShotSummary> listShots(UUID tenantId, UUID projectId);

    /** The project's aspect ratio (RATIO_16_9, RATIO_9_16, ...), or null when it has no config yet.
     * The shape every shot is padded to when they are joined. */
    String getAspectRatio(UUID tenantId, UUID projectId);

    PreProductionShotDetails getShotDialogue(UUID tenantId, UUID projectId, UUID scriptId, String shotRef);
}
