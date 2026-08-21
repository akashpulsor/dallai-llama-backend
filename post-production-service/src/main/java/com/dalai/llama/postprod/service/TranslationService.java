package com.dalai.llama.postprod.service;

public interface TranslationService {

    String translate(String tenantId, String idempotencyKey, String transcript, String sourceLanguage, String targetLanguage);
}
