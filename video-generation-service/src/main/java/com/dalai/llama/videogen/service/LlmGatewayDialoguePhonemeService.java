package com.dalai.llama.videogen.service;

import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.web.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Same line-scoped word-boundary respelling logic proven in creator-service's
 * ScreenplayVideoProviderGenerationService this session, reimplemented here since this service is
 * standalone -- the phoneme-guide instruction text itself lives in llm-gateway's PHONEME_GUIDE
 * prompt_template (§2.1), not hardcoded here.
 */
@Service
public class LlmGatewayDialoguePhonemeService implements DialoguePhonemeService {

    private final LlmGatewayClient llmGatewayClient;
    private final String defaultModel;

    public LlmGatewayDialoguePhonemeService(
            LlmGatewayClient llmGatewayClient,
            @Value("${video-gen.llm-gateway.default-compression-model}") String defaultModel
    ) {
        this.llmGatewayClient = llmGatewayClient;
        this.defaultModel = defaultModel;
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
        UUID tenantId = TenantContextHolder.get().tenantId();
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "phoneme-guide-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(
                        defaultModel,
                        List.of(new LlmGatewayMessage("user", dialogueLine)),
                        Map.of(),
                        "PHONEME_GUIDE",
                        Map.of("languageCode", languageCode == null ? "" : languageCode)
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
            if (clean.isBlank()) {
                continue;
            }
            String separator = clean.contains("=>") ? "=>" : "=";
            int separatorIndex = clean.indexOf(separator);
            if (separatorIndex <= 0 || separatorIndex + separator.length() >= clean.length()) {
                continue;
            }
            String term = clean.substring(0, separatorIndex).trim();
            String respelling = clean.substring(separatorIndex + separator.length()).trim();
            if (term.isBlank() || respelling.isBlank()) {
                continue;
            }
            result = result.replaceAll(
                    "(?iu)\\b" + Pattern.quote(term) + "\\b",
                    Matcher.quoteReplacement(respelling)
            );
        }
        return result;
    }
}
