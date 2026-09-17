package com.dalai.llama.videogen.kafka;

import java.util.UUID;

/**
 * Published when a dub is requested, consumed by this same service's worker side.
 *
 * <p>The text travels in the payload rather than being re-read at consume time, and that is
 * deliberate: it is the one thing here that is a decision rather than a fact. A creator who
 * rephrases a line and dubs it is asking for THOSE words, and a consumer re-reading the shot a
 * minute later could find them already changed again -- recording something nobody asked for.
 */
public record DubRequestedEvent(UUID jobId, String tenantId, UUID projectId, UUID shotId,
                                String text, UUID userId) {
}
