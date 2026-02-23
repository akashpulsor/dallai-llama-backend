package com.dalai.llama.tenant.dto.response;

import lombok.Builder;

import java.math.BigDecimal;

/**
 * AI provider configuration for the plan.
 * Maps from plan_ai_configs and ai_providers tables.
 */
@Builder
public record AiConfigResponse(
        String aiStackType,           // BUDGET, STANDARD, PREMIUM

        // STT (Speech-to-Text)
        String sttProvider,           // Groq, Deepgram
        String sttModel,              // whisper-large-v3, nova-2
        BigDecimal sttCostPerMin,

        // TTS (Text-to-Speech)
        String ttsProvider,           // Google, OpenAI, ElevenLabs
        String ttsModel,              // standard, tts-1, eleven_multilingual_v2
        BigDecimal ttsCostPerMin,

        // LLM (Large Language Model)
        String llmProvider,           // Groq, OpenAI
        String llmModel,              // llama-3.1-70b, gpt-4o
        BigDecimal llmCostPerMin,

        // Total AI cost per minute
        BigDecimal totalAiCostPerMin
) {}