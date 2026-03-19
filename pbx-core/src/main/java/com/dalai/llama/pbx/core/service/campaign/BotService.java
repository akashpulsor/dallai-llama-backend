package com.dalai.llama.pbx.core.service.campaign;


import com.dalai.llama.pbx.core.domain.entity.campaign.Bot;
import com.dalai.llama.pbx.core.domain.enums.BotStatus;
import com.dalai.llama.pbx.core.repository.campaign.BotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bot (AI conversation config) management.
 *
 * Bots are reusable across campaigns. A bot defines:
 *   - LLM config: system prompt, guidelines, allowed intents, fallback message
 *   - Voice config: provider, voice ID, speed, language
 *   - Escalation: rules for when to transfer to human agent
 *   - Behavior: max turns, DTMF, barge-in, sentiment tracking
 *
 * At runtime, voice-brain (Pipecat) fetches bot config via:
 *   GET /internal/ai/config/{tenantId}?botId={botId}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotService {

    private final BotRepository botRepository;

    @Transactional
    public Bot createBot(UUID tenantId, UUID subscriptionId, String name,
                         String systemPrompt, String greetingMessage, String goodbyeMessage,
                         List<String> guidelines, List<String> allowedIntents,
                         String fallbackMessage, Map<String, Object> escalationRules,
                         String transferTarget, String transferType,
                         String voiceProvider, String voiceId, BigDecimal voiceSpeed,
                         String language, Integer maxTurns, Integer maxDurationSeconds,
                         Boolean dtmfEnabled, Boolean bargeInEnabled,
                         Boolean sentimentTracking, Map<String, Object> customData) {

        if (botRepository.existsByTenantIdAndName(tenantId, name)) {
            throw new IllegalArgumentException("Bot '" + name + "' already exists");
        }

        Bot bot = Bot.builder()
                .tenantId(tenantId)
                .subscriptionId(subscriptionId)
                .name(name)
                .systemPrompt(systemPrompt)
                .greetingMessage(greetingMessage)
                .goodbyeMessage(goodbyeMessage)
                .guidelines(guidelines != null ? guidelines : List.of())
                .allowedIntents(allowedIntents != null ? allowedIntents : List.of())
                .fallbackMessage(fallbackMessage)
                .escalationRules(escalationRules != null ? escalationRules : Map.of())
                .transferTarget(transferTarget)
                .voiceProvider(voiceProvider != null ? voiceProvider : "piper")
                .voiceId(voiceId)
                .voiceSpeed(voiceSpeed != null ? voiceSpeed : BigDecimal.ONE)
                .language(language != null ? language : "en")
                .maxTurns(maxTurns != null ? maxTurns : 30)
                .maxDurationSeconds(maxDurationSeconds != null ? maxDurationSeconds : 600)
                .dtmfEnabled(dtmfEnabled != null ? dtmfEnabled : true)
                .bargeInEnabled(bargeInEnabled != null ? bargeInEnabled : true)
                .sentimentTracking(sentimentTracking != null ? sentimentTracking : false)
                .customData(customData != null ? customData : Map.of())
                .build();

        bot = botRepository.save(bot);
        log.info("Created bot '{}' for tenant {}", name, tenantId);
        return bot;
    }

    @Transactional
    public Bot updateBot(UUID botId, Map<String, Object> updates) {
        Bot bot = botRepository.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("Bot not found: " + botId));

        // Apply updates (only non-null values)
        if (updates.containsKey("name")) bot.setName((String) updates.get("name"));
        if (updates.containsKey("system_prompt")) bot.setSystemPrompt((String) updates.get("system_prompt"));
        if (updates.containsKey("greeting_message")) bot.setGreetingMessage((String) updates.get("greeting_message"));
        if (updates.containsKey("goodbye_message")) bot.setGoodbyeMessage((String) updates.get("goodbye_message"));
        if (updates.containsKey("fallback_message")) bot.setFallbackMessage((String) updates.get("fallback_message"));
        if (updates.containsKey("voice_provider")) bot.setVoiceProvider((String) updates.get("voice_provider"));
        if (updates.containsKey("voice_id")) bot.setVoiceId((String) updates.get("voice_id"));
        if (updates.containsKey("language")) bot.setLanguage((String) updates.get("language"));
        if (updates.containsKey("transfer_target")) bot.setTransferTarget((String) updates.get("transfer_target"));

        @SuppressWarnings("unchecked")
        List<String> guidelines = (List<String>) updates.get("guidelines");
        if (guidelines != null) bot.setGuidelines(guidelines);

        @SuppressWarnings("unchecked")
        Map<String, Object> escalation = (Map<String, Object>) updates.get("escalation_rules");
        if (escalation != null) bot.setEscalationRules(escalation);

        @SuppressWarnings("unchecked")
        Map<String, Object> custom = (Map<String, Object>) updates.get("custom_data");
        if (custom != null) bot.setCustomData(custom);

        return botRepository.save(bot);
    }

    @Transactional
    public void activateBot(UUID botId) {
        Bot bot = botRepository.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("Bot not found: " + botId));
        bot.setStatus(BotStatus.ACTIVE);
        botRepository.save(bot);
        log.info("Activated bot {}", botId);
    }

    @Transactional
    public void disableBot(UUID botId) {
        Bot bot = botRepository.findById(botId)
                .orElseThrow(() -> new IllegalArgumentException("Bot not found: " + botId));
        bot.setStatus(BotStatus.DISABLED);
        botRepository.save(bot);
        log.info("Disabled bot {}", botId);
    }

    public List<Bot> getByTenantId(UUID tenantId) {
        return botRepository.findByTenantId(tenantId);
    }

    public List<Bot> getActiveBots(UUID tenantId) {
        return botRepository.findByTenantIdAndStatus(tenantId, BotStatus.ACTIVE);
    }

    public Optional<Bot> getById(UUID botId) {
        return botRepository.findById(botId);
    }
}