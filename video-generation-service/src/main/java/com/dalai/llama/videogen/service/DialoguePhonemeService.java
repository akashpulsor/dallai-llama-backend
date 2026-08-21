package com.dalai.llama.videogen.service;

public interface DialoguePhonemeService {

    /** Respells the dialogue line for correct TTS pronunciation -- reuses the same phoneme-guide
     * approach proven in creator-service's ScreenplayVideoProviderGenerationService, but via
     * llm-gateway's PHONEME_GUIDE task template instead of a hardcoded prompt. */
    String respellDialogue(String dialogueLine, String languageCode);
}
