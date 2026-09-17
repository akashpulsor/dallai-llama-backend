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
    /**
     * The finished film has been published to the client's review page and is waiting to be
     * watched.
     *
     * <p>Set when a creator publishes in post-production, not when the film finishes joining: a cut
     * that exists is not a cut anyone has been shown, and the difference between those two is the
     * whole point of publishing being a separate press.
     *
     * <p>Sits between VIDEO_GENERATION_COMPLETE and CLIENT_LOCKED because that is the order the work
     * happens in -- every shot generated, the film cut and shown, then the client locks it.
     */
    READY_FOR_REVIEW,
    /** The client has reviewed the full creative package on their public review page and locked
     * it -- this is what triggers embedding the package into chat-service and opening the
     * client-facing chat. Regenerating any stage still moves status backward same as before. */
    CLIENT_LOCKED
}
