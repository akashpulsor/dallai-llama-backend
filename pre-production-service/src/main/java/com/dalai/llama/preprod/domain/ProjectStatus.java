package com.dalai.llama.preprod.domain;

public enum ProjectStatus {
    DRAFT,
    SCRIPT_READY,
    SCREENPLAY_READY,
    SHOT_LIST_READY,
    IN_PRODUCTION,
    /** The client has reviewed the full creative package on their public review page and locked
     * it -- this is what triggers embedding the package into chat-service and opening the
     * client-facing chat. Regenerating any stage still moves status backward same as before. */
    CLIENT_LOCKED
}
