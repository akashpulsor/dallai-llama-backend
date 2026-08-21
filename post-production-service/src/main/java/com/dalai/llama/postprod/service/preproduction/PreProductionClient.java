package com.dalai.llama.postprod.service.preproduction;

import java.util.UUID;

/** The one and only way DialogueSyncCoordinator learns what a shot's dialogue actually is --
 * pre-production-service (a separate, not-yet-built service with its own DB) is the sole source
 * of truth for this. Behind an interface so the real implementation can land later without
 * DialogueSyncCoordinator changing at all. */
public interface PreProductionClient {

    PreProductionShotDetails getShotDialogue(UUID projectId, UUID scriptId, String shotRef);
}
