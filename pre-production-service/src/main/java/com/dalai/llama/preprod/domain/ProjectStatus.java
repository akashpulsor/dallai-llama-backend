package com.dalai.llama.preprod.domain;

public enum ProjectStatus {
    DRAFT,
    SCRIPT_READY,
    SCREENPLAY_READY,
    SHOT_LIST_READY,
    IN_PRODUCTION,
    /** Every shot's video has finished generating in video-generation-service. Frontend-driven:
     * the creator-ui shot-generation flow observes every shot reach COMPLETED itself (it already
     * polls per-shot status) and calls PATCH /v1/projects/{id}/status -- nothing here polls
     * video-generation-service on the backend's behalf, same "frontend drives, backend just
     * persists" shape every other stage transition already uses. Surfaces the "Move to
     * Post-Production" CTA. */
    VIDEO_GENERATION_COMPLETE,
    /** The client has reviewed the full creative package on their public review page and locked
     * it -- this is what triggers embedding the package into chat-service and opening the
     * client-facing chat. Regenerating any stage still moves status backward same as before. */
    CLIENT_LOCKED
}
