package com.dalai.llama.llmgateway.service.prompt;

/**
 * Respells a dialogue line using llm-gateway's own PHONEME_GUIDE prompt template so a TTS/video
 * model pronounces brand names, acronyms, and non-English words correctly. Moved from
 * video-generation-service (was {@code LlmGatewayDialoguePhonemeService} making an HTTP call
 * back to llm-gateway) -- now runs in-process here since llm-gateway is where the template
 * lives.
 */
public interface DialoguePhonemeService {

    String respellDialogue(String dialogueLine, String languageCode);
}
