package com.dalai.llama.postprod.service;

import java.util.UUID;

/** Same standalone-and-testable status as FoleyGenerationService -- see its class comment. */
public interface MusicGenerationService {

    AudioGenerationResult generateMusic(UUID tenantId, String idempotencyKey, String moodPrompt, Integer durationSeconds, String modelOverride);
}
