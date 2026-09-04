package com.dalai.llama.llmgateway.service.prompt;

import com.dalai.llama.llmgateway.dto.ChatMessage;
import com.dalai.llama.llmgateway.dto.ChatRequest;
import com.dalai.llama.llmgateway.dto.ChatResponse;
import com.dalai.llama.llmgateway.service.LlmGatewayService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-process implementation -- calls {@link LlmGatewayService#chat} directly instead of an HTTP
 * loop-back like video-gen used to do. Renders the PHONEME_GUIDE prompt_template, applies its
 * {@code term => respelling} directives to the dialogue line via word-boundary substitution
 * (same logic as creator-service's ScreenplayVideoProviderGenerationService).
 */
@Service
public class LlmGatewayDialoguePhonemeService implements DialoguePhonemeService {

    private final LlmGatewayService llmGatewayService;
    private final String defaultModel;
    private final String phonemeTenantId;

    public LlmGatewayDialoguePhonemeService(
            LlmGatewayService llmGatewayService,
            @Value("${llm-gateway.prompt-format.phoneme-model:gemini-2.5-flash}") String defaultModel,
            @Value("${llm-gateway.prompt-format.phoneme-tenant-id:00000000-0000-0000-0000-000000000000}") String phonemeTenantId
    ) {
        this.llmGatewayService = llmGatewayService;
        this.defaultModel = defaultModel;
        this.phonemeTenantId = phonemeTenantId;
    }

    @Override
    public String respellDialogue(String dialogueLine, String languageCode) {
        if (dialogueLine == null || dialogueLine.isBlank()) {
            return dialogueLine;
        }
        String guide = generatePhonemeGuide(dialogueLine, languageCode);
        return applyRespelling(dialogueLine, guide);
    }

    private String generatePhonemeGuide(String dialogueLine, String languageCode) {
        ChatResponse response = llmGatewayService.chat(
                phonemeTenantId,
                "phoneme-guide-" + UUID.randomUUID(),
                new ChatRequest(
                        null,
                        defaultModel,
                        List.of(new ChatMessage("user", dialogueLine)),
                        Map.of(),
                        null,
                        null,
                        "PHONEME_GUIDE",
                        Map.of("languageCode", languageCode == null ? "" : languageCode),
                        null
                )
        );
        return response == null || response.response() == null ? "" : response.response();
    }

    private String applyRespelling(String text, String guide) {
        String result = text;
        if (guide == null || guide.isBlank()) {
            return result;
        }
        for (String line : guide.split("\\R")) {
            String clean = line == null ? "" : line.trim();
            if (clean.isBlank()) continue;
            String separator = clean.contains("=>") ? "=>" : "=";
            int separatorIndex = clean.indexOf(separator);
            if (separatorIndex <= 0 || separatorIndex + separator.length() >= clean.length()) continue;
            String term = clean.substring(0, separatorIndex).trim();
            String respelling = clean.substring(separatorIndex + separator.length()).trim();
            if (term.isBlank() || respelling.isBlank()) continue;
            result = result.replaceAll(
                    "(?iu)\\b" + Pattern.quote(term) + "\\b",
                    Matcher.quoteReplacement(respelling)
            );
        }
        return result;
    }
}
