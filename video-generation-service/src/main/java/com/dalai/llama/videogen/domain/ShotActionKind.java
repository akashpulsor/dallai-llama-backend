package com.dalai.llama.videogen.domain;

/** What a required shot action is. OPENING_STATE and ENDING_STATE bracket every shot. */
public enum ShotActionKind {
    OPENING_STATE,
    ACTION,
    DIALOGUE,
    CAMERA,
    ENDING_STATE
}
