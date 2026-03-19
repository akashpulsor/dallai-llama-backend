package com.dalai.llama.pbx.core.controller.campaign;



import com.dalai.llama.pbx.core.domain.entity.campaign.Bot;
import com.dalai.llama.pbx.core.domain.entity.campaign.BotEscalationIntent;
import com.dalai.llama.pbx.core.domain.entity.campaign.BotKnowledgeDocument;
import com.dalai.llama.pbx.core.repository.campaign.BotEscalationIntentRepository;
import com.dalai.llama.pbx.core.repository.campaign.BotKnowledgeDocumentRepository;
import com.dalai.llama.pbx.core.service.campaign.BotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bot CRUD + nested resources:
 *
 *   /api/v1/bots                                      — Bot CRUD
 *   /api/v1/bots/{botId}/escalation-intents           — Per-bot escalation intent thresholds
 *   /api/v1/bots/{botId}/knowledge                    — Per-bot RAG knowledge documents
 *
 * Admin UI uses these to configure:
 *   - Bot personality (system prompt, greeting, guidelines)
 *   - What intents to track and when to escalate
 *   - Knowledge base for grounded answers (RAG)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/bots")
@RequiredArgsConstructor
public class BotController {

    private final BotService botService;
    private final BotEscalationIntentRepository escalationIntentRepo;
    private final BotKnowledgeDocumentRepository knowledgeDocRepo;

    // ═══════════════════════════════════════════════════════════
    // BOT CRUD
    // ═══════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    @PostMapping
    public ResponseEntity<Bot> create(@RequestBody Map<String, Object> body) {
        Bot bot = botService.createBot(
                UUID.fromString(body.get("tenant_id").toString()),
                UUID.fromString(body.get("subscription_id").toString()),
                (String) body.get("name"),
                (String) body.get("system_prompt"),
                (String) body.get("greeting_message"),
                (String) body.get("goodbye_message"),
                (List<String>) body.get("guidelines"),
                (List<String>) body.get("allowed_intents"),
                (String) body.get("fallback_message"),
                (Map<String, Object>) body.get("escalation_rules"),
                (String) body.get("transfer_target"),
                (String) body.get("transfer_type"),
                (String) body.get("voice_provider"),
                (String) body.get("voice_id"),
                body.get("voice_speed") instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null,
                (String) body.get("language"),
                body.get("max_turns") instanceof Number n ? n.intValue() : null,
                body.get("max_duration_seconds") instanceof Number n ? n.intValue() : null,
                body.get("dtmf_enabled") instanceof Boolean b ? b : null,
                body.get("barge_in_enabled") instanceof Boolean b ? b : null,
                body.get("sentiment_tracking") instanceof Boolean b ? b : null,
                (Map<String, Object>) body.get("custom_data")
        );
        return ResponseEntity.ok(bot);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Bot> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(botService.updateBot(id, body));
    }

    @PutMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable UUID id) {
        botService.activateBot(id);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        botService.disableBot(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<Bot>> list(@RequestParam UUID tenant_id) {
        return ResponseEntity.ok(botService.getByTenantId(tenant_id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Bot> get(@PathVariable UUID id) {
        return botService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════
    // ESCALATION INTENTS — per-bot
    // ═══════════════════════════════════════════════════════════

    /**
     * List escalation intents for a bot.
     * GET /api/v1/bots/{botId}/escalation-intents
     */
    @GetMapping("/{botId}/escalation-intents")
    public ResponseEntity<List<BotEscalationIntent>> listEscalationIntents(@PathVariable UUID botId) {
        return ResponseEntity.ok(escalationIntentRepo.findByBotIdAndEnabledTrueOrderByPriorityDesc(botId));
    }

    /**
     * Add escalation intent.
     * POST /api/v1/bots/{botId}/escalation-intents
     * { "intent_name": "interested", "threshold": 0.85, "action": "TRANSFER_QUEUE", "target": "sales", "description": "..." }
     */
    @PostMapping("/{botId}/escalation-intents")
    public ResponseEntity<BotEscalationIntent> addEscalationIntent(
            @PathVariable UUID botId, @RequestBody Map<String, Object> body) {

        Bot bot = botService.getById(botId).orElse(null);
        if (bot == null) return ResponseEntity.notFound().build();

        BotEscalationIntent intent = BotEscalationIntent.builder()
                .botId(botId)
                .tenantId(bot.getTenantId())
                .intentName((String) body.get("intent_name"))
                .description((String) body.get("description"))
                .threshold(body.get("threshold") instanceof Number n
                        ? BigDecimal.valueOf(n.doubleValue()) : new BigDecimal("0.900"))
                .action((String) body.getOrDefault("action", "TRANSFER_QUEUE"))
                .target((String) body.get("target"))
                .priority(body.get("priority") instanceof Number n ? n.intValue() : 0)
                .build();

        intent = escalationIntentRepo.save(intent);
        log.info("Added escalation intent: bot={} intent={} threshold={} action={} target={}",
                botId, intent.getIntentName(), intent.getThreshold(), intent.getAction(), intent.getTarget());
        return ResponseEntity.ok(intent);
    }

    /**
     * Update escalation intent.
     * PUT /api/v1/bots/{botId}/escalation-intents/{intentId}
     */
    @PutMapping("/{botId}/escalation-intents/{intentId}")
    public ResponseEntity<BotEscalationIntent> updateEscalationIntent(
            @PathVariable UUID botId, @PathVariable UUID intentId, @RequestBody Map<String, Object> body) {

        return escalationIntentRepo.findById(intentId)
                .map(intent -> {
                    if (body.containsKey("intent_name")) intent.setIntentName((String) body.get("intent_name"));
                    if (body.containsKey("description")) intent.setDescription((String) body.get("description"));
                    if (body.get("threshold") instanceof Number n) intent.setThreshold(BigDecimal.valueOf(n.doubleValue()));
                    if (body.containsKey("action")) intent.setAction((String) body.get("action"));
                    if (body.containsKey("target")) intent.setTarget((String) body.get("target"));
                    if (body.get("priority") instanceof Number n) intent.setPriority(n.intValue());
                    if (body.get("enabled") instanceof Boolean b) intent.setEnabled(b);
                    return ResponseEntity.ok(escalationIntentRepo.save(intent));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete escalation intent.
     * DELETE /api/v1/bots/{botId}/escalation-intents/{intentId}
     */
    @DeleteMapping("/{botId}/escalation-intents/{intentId}")
    public ResponseEntity<Void> deleteEscalationIntent(@PathVariable UUID botId, @PathVariable UUID intentId) {
        escalationIntentRepo.deleteById(intentId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════
    // KNOWLEDGE DOCUMENTS — per-bot (RAG)
    // ═══════════════════════════════════════════════════════════

    /**
     * List knowledge docs for a bot.
     * GET /api/v1/bots/{botId}/knowledge
     */
    @GetMapping("/{botId}/knowledge")
    public ResponseEntity<List<BotKnowledgeDocument>> listKnowledge(@PathVariable UUID botId) {
        return ResponseEntity.ok(knowledgeDocRepo.findByBotIdAndEnabledTrueOrderByDisplayOrder(botId));
    }

    /**
     * Add knowledge document.
     * POST /api/v1/bots/{botId}/knowledge
     * { "title": "Pricing", "content": "Basic plan Rs 999/mo...", "content_type": "PRODUCT" }
     */
    @PostMapping("/{botId}/knowledge")
    public ResponseEntity<BotKnowledgeDocument> addKnowledge(
            @PathVariable UUID botId, @RequestBody Map<String, Object> body) {

        Bot bot = botService.getById(botId).orElse(null);
        if (bot == null) return ResponseEntity.notFound().build();

        BotKnowledgeDocument doc = BotKnowledgeDocument.builder()
                .botId(botId)
                .tenantId(bot.getTenantId())
                .title((String) body.get("title"))
                .content((String) body.get("content"))
                .contentType((String) body.getOrDefault("content_type", "FAQ"))
                .language((String) body.getOrDefault("language", "en"))
                .displayOrder(body.get("display_order") instanceof Number n ? n.intValue() : 0)
                .build();

        doc = knowledgeDocRepo.save(doc);
        log.info("Added knowledge doc: bot={} title={} type={}", botId, doc.getTitle(), doc.getContentType());
        return ResponseEntity.ok(doc);
    }

    /**
     * Update knowledge document.
     * PUT /api/v1/bots/{botId}/knowledge/{docId}
     */
    @PutMapping("/{botId}/knowledge/{docId}")
    public ResponseEntity<BotKnowledgeDocument> updateKnowledge(
            @PathVariable UUID botId, @PathVariable UUID docId, @RequestBody Map<String, Object> body) {

        return knowledgeDocRepo.findById(docId)
                .map(doc -> {
                    if (body.containsKey("title")) doc.setTitle((String) body.get("title"));
                    if (body.containsKey("content")) doc.setContent((String) body.get("content"));
                    if (body.containsKey("content_type")) doc.setContentType((String) body.get("content_type"));
                    if (body.containsKey("language")) doc.setLanguage((String) body.get("language"));
                    if (body.get("enabled") instanceof Boolean b) doc.setEnabled(b);
                    if (body.get("display_order") instanceof Number n) doc.setDisplayOrder(n.intValue());
                    return ResponseEntity.ok(knowledgeDocRepo.save(doc));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete knowledge document.
     * DELETE /api/v1/bots/{botId}/knowledge/{docId}
     */
    @DeleteMapping("/{botId}/knowledge/{docId}")
    public ResponseEntity<Void> deleteKnowledge(@PathVariable UUID botId, @PathVariable UUID docId) {
        knowledgeDocRepo.deleteById(docId);
        return ResponseEntity.noContent().build();
    }
}