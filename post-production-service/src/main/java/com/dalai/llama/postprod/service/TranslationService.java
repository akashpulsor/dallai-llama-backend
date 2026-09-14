package com.dalai.llama.postprod.service;

import java.util.UUID;

public interface TranslationService {

    /** {@code projectId} attributes this call's cost to the project that caused it in
     * llm-gateway's llm_job log. Null for a standalone call that genuinely has no project. */
    String translate(String tenantId, UUID projectId, String idempotencyKey, String transcript, String sourceLanguage, String targetLanguage);
}
