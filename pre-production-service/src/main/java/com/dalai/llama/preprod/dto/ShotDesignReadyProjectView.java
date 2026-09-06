package com.dalai.llama.preprod.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** One row per project that has at least one shot -- the "ready for post-production handoff"
 * list creator-ui's Planner page shows. Replaces creator-service's own equivalent (dead,
 * decommissioned along with the rest of that service's storyboard/shot-take tables); sourced
 * entirely from this service's own live shot + shot-image data instead. Per-shot thumbnails
 * (storyboard/lighting/camera-plan) come from ShotImageService directly -- a plain in-process
 * method call, not a separate HTTP hop, since both live in this same service -- so there's no
 * network fan-out cost to worry about here, just one extra DB read per shot. */
public record ShotDesignReadyProjectView(
        UUID projectId,
        String title,
        String status,
        OffsetDateTime updatedAt,
        int shotCount,
        List<ShotDesignSummaryView> shots
) {
}
