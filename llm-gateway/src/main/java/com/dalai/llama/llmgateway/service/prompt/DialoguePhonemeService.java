package com.dalai.llama.llmgateway.service.prompt;

/**
 * Respells a dialogue line using llm-gateway's own PHONEME_GUIDE prompt template so a TTS/video
 * model pronounces brand names, acronyms, and non-English words correctly. Moved from
 * video-generation-service (was {@code LlmGatewayDialoguePhonemeService} making an HTTP call
 * back to llm-gateway) -- now runs in-process here since llm-gateway is where the template
 * lives.
 */
public interface DialoguePhonemeService {

    /** {@code tenantId} is the real calling tenant, forwarded from the prompt-format request's
     * X-Tenant-ID header -- this respelling is a billable LLM call, so it must be charged and
     * wallet-checked against the tenant that asked for it, never a placeholder. */
    String respellDialogue(String tenantId, String dialogueLine, String languageCode);
}
