package com.dalai.llama.preprod.dto;

/** {@code dialogue}/{@code captions} are per-call overrides of video-generation-service's own
 * feature flags (see {@code VideoFeatureFlagDefinition} for the master data a UI renders these
 * from) -- null means "use the project's default", same override-only-what-you-set convention as
 * everywhere else in this service. */
public record DispatchShotRequest(boolean autoApprove, Boolean dialogue, Boolean captions) {
}
