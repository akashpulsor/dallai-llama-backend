package com.dalai.llama.tenant.showcase.dto;

/** Sent by the player: once after 3 seconds of playing, and again with {@code completed=true}
 * when the video ends. */
public record RecordPlayRequest(boolean completed) {
}
