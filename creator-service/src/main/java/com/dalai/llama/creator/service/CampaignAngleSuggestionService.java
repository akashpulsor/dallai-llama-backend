package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorPromptRun;
import com.dalai.llama.creator.dto.request.CampaignAngleSuggestionRequest;
import com.dalai.llama.creator.dto.response.CampaignAngleSuggestionResponse;
import com.dalai.llama.creator.repository.CreatorPromptRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CampaignAngleSuggestionService {

    private static final String PROMPT_TYPE = "CAMPAIGN_ANGLE_SUGGEST";
    private static final int ANGLE_COUNT = 3;

    private final CreatorAiService creatorAiService;
    private final CreatorPromptRunRepository promptRunRepository;
    private final ObjectMapper objectMapper;
    private final IdeaService ideaService;
    private final CampaignAngleCriticService campaignAngleCriticService;

    public CampaignAngleSuggestionService(
            CreatorAiService creatorAiService,
            CreatorPromptRunRepository promptRunRepository,
            ObjectMapper objectMapper,
            IdeaService ideaService,
            CampaignAngleCriticService campaignAngleCriticService
    ) {
        this.creatorAiService = creatorAiService;
        this.promptRunRepository = promptRunRepository;
        this.objectMapper = objectMapper;
        this.ideaService = ideaService;
        this.campaignAngleCriticService = campaignAngleCriticService;
    }

    public CampaignAngleSuggestionResponse suggest(
            CampaignAngleSuggestionRequest request,
            String tenantId,
            String userId
    ) {
        CampaignAngleSuggestionRequest safeRequest = request == null
                ? new CampaignAngleSuggestionRequest(null, null, null, null, null, null, null, null, null, null, null, null, List.of(), Map.of(), Map.of())
                : request;
        Map<String, Object> productBrief = safeMap(safeRequest.productIntelligenceBrief());
        String ideaText = text(safeRequest.ideaText());
        if (ideaText.isBlank() && productBrief.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write a topic or provide a product brief before generating campaign angles.");
        }

        String safeTenantId = text(tenantId).isBlank() ? "unknown" : text(tenantId);
        String safeUserId = text(userId).isBlank() ? "anonymous" : text(userId);
        Map<String, Object> snapshot = inputSnapshot(safeRequest, ideaText, productBrief);
        // Product briefs carry their reference photos separately from the text brief -
        // without this, Gemini only ever sees a JSON description of the product, never
        // the actual images, and angles end up generic instead of grounded in what the
        // product actually looks like. Reuses the same extraction IdeaService already
        // relies on for idea/script generation so all three stay consistent.
        List<String> referenceImageUrls = ideaService.productReferenceImageUrls(Map.of("productIntelligenceBrief", productBrief));
        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                safeTenantId,
                safeUserId,
                safeRequest.projectId(),
                null,
                null
        );

        // Critic-gated retry: a FAIL verdict (generic/off-brief/vague-visual-direction batch)
        // triggers exactly one regeneration with the critic's issues folded into the prompt,
        // never more - angle suggestion is a cheap, small output, not worth a bigger budget.
        AngleAttempt first = generateAndScoreAngles(snapshot, referenceImageUrls, usageContext, safeTenantId, safeUserId, safeRequest.projectId(), "");
        if (first.angles().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The AI response did not contain usable campaign angles. Please try again.");
        }
        AngleAttempt best = first;
        if (first.critique() != null && "FAIL".equals(first.critique().status())) {
            AngleAttempt retry = generateAndScoreAngles(snapshot, referenceImageUrls, usageContext, safeTenantId, safeUserId, safeRequest.projectId(), feedbackText(first.critique()));
            if (!retry.angles().isEmpty() && retry.averageScore() >= best.averageScore()) {
                best = retry;
            }
        }

        return new CampaignAngleSuggestionResponse(
                best.angles(),
                creatorAiService.providerName(),
                creatorAiService.modelName(),
                best.promptRunId()
        );
    }

    private record AngleAttempt(
            List<CampaignAngleSuggestionResponse.CampaignAngle> angles,
            UUID promptRunId,
            double averageScore,
            CampaignAngleCriticService.CampaignAngleCriticResult critique
    ) {
    }

    /** One generation call + critique + persistence + billing. Each call here is a real, billed AI request. */
    private AngleAttempt generateAndScoreAngles(
            Map<String, Object> snapshot,
            List<String> referenceImageUrls,
            CreatorAiService.AiUsageContext usageContext,
            String safeTenantId,
            String safeUserId,
            UUID projectId,
            String priorFeedback
    ) {
        String renderedPrompt = renderedPrompt(snapshot, priorFeedback);
        Map<String, Object> providerInput = new LinkedHashMap<>(snapshot);
        providerInput.put("renderedPrompt", renderedPrompt);
        providerInput.put("referenceImageUrls", referenceImageUrls);
        providerInput.put("productReferenceImageUrls", referenceImageUrls);
        providerInput.put("attachReferenceImages", !referenceImageUrls.isEmpty());

        CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(PROMPT_TYPE, providerInput, usageContext);
        List<CampaignAngleSuggestionResponse.CampaignAngle> angles = anglesFrom(aiResponse.output());
        if (angles.isEmpty()) {
            return new AngleAttempt(List.of(), null, 0, null);
        }
        CampaignAngleCriticService.CampaignAngleCriticResult critique = campaignAngleCriticService.critique(
                angles, snapshot, safeTenantId, safeUserId, projectId
        );

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put("angles", angles);
        outputPayload.put("providerOutput", aiResponse.output());
        outputPayload.put("critique", critique);
        CreatorPromptRun promptRun = promptRunRepository.save(CreatorPromptRun.builder()
                .tenantId(safeTenantId)
                .userId(safeUserId)
                .projectId(projectId)
                .promptTemplateKey(PROMPT_TYPE)
                .promptTemplateVersion(1)
                .renderedPrompt(renderedPrompt)
                .inputSnapshot(snapshot)
                .provider(creatorAiService.providerName())
                .model(creatorAiService.modelName())
                .outputPayload(outputPayload)
                .tokenMetadata(aiResponse.tokenMetadata())
                .costMetadata(aiResponse.costMetadata())
                .status("COMPLETED")
                .completedAt(OffsetDateTime.now())
                .build());
        creatorAiService.publishBillingDebit(PROMPT_TYPE, aiResponse, usageContext.withPromptRunId(promptRun.getId()));

        return new AngleAttempt(angles, promptRun.getId(), critique.averageScore(), critique);
    }

    private String feedbackText(CampaignAngleCriticService.CampaignAngleCriticResult critique) {
        if (critique == null || critique.angleAudits().isEmpty()) {
            return "";
        }
        List<String> issues = new ArrayList<>();
        for (CampaignAngleCriticService.AngleAudit audit : critique.angleAudits()) {
            issues.addAll(audit.issues());
        }
        return issues.isEmpty() ? critique.summary() : String.join("; ", issues);
    }

    private Map<String, Object> inputSnapshot(
            CampaignAngleSuggestionRequest request,
            String ideaText,
            Map<String, Object> productBrief
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ideaText", ideaText);
        snapshot.put("topicType", text(request.topicType()));
        snapshot.put("adFormat", text(request.adFormat()));
        snapshot.put("campaignObjective", text(request.campaignObjective()));
        snapshot.put("targetAudience", text(request.targetAudience()));
        snapshot.put("productionStyle", text(request.productionStyle()));
        snapshot.put("dialogueLanguage", text(request.dialogueLanguage()));
        snapshot.put("screenType", text(request.screenType()));
        snapshot.put("storytellingType", text(request.storytellingType()));
        snapshot.put("hookLens", text(request.hookLens()));
        snapshot.put("durationSeconds", request.durationSeconds() == null ? 30 : request.durationSeconds());
        snapshot.put("selectedShotTypes", cleanStrings(request.selectedShotTypes()));
        snapshot.put("brandContext", safeMap(request.brandContext()));
        snapshot.put("productIntelligenceBrief", productBrief);
        return snapshot;
    }

    private String renderedPrompt(Map<String, Object> snapshot, String priorFeedback) {
        // priorFeedback is AI-generated critic text and may itself contain a literal '%' -
        // it must stay a %s ARGUMENT to .formatted(), never get concatenated into the
        // template string that .formatted() re-scans for directives, or a stray '%' in the
        // feedback text (e.g. "only 20% off") would throw UnknownFormatConversionException.
        String feedbackSection = priorFeedback == null || priorFeedback.isBlank()
                ? ""
                : "\nA prior attempt at this brief was reviewed and rejected. Fix these specific issues this time:\n%s\n".formatted(priorFeedback);
        return """
                You are a senior creative strategist. Generate exactly three distinct, practical campaign angles for one short-form video brief.

                Brief JSON:
                %s
                %s

                Rules:
                - Return strict JSON only. Do not include markdown.
                - The three angles must differ in persuasion mechanism, hook, and visual approach.
                - Respect the ad format, production style, selected shot recipe, brand restrictions, and product facts in the brief.
                - Do not invent product claims, pricing, reviews, ingredients, endorsements, or competitor facts.
                - Make every angle usable as the controlling creative direction for story, screenplay, storyboard, audio, and video generation.
                - Keep each title under 60 characters and descriptions under 240 characters.

                Return exactly this JSON shape:
                {
                  "angles": [
                    {
                      "title": "",
                      "description": "",
                      "hook": "",
                      "visualDirection": "",
                      "selectionReason": ""
                    }
                  ]
                }
                """.formatted(toJson(snapshot), feedbackSection);
    }

    private List<CampaignAngleSuggestionResponse.CampaignAngle> anglesFrom(Map<String, Object> output) {
        List<Map<String, Object>> candidates = maps(output == null ? null : output.get("angles"));
        List<CampaignAngleSuggestionResponse.CampaignAngle> angles = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            String title = truncate(text(candidate.get("title")), 60);
            String description = truncate(text(candidate.get("description")), 240);
            if (title.isBlank() || description.isBlank()) {
                continue;
            }
            angles.add(new CampaignAngleSuggestionResponse.CampaignAngle(
                    "angle-" + (angles.size() + 1),
                    title,
                    description,
                    truncate(text(candidate.get("hook")), 180),
                    truncate(text(candidate.get("visualDirection")), 220),
                    truncate(text(candidate.get("selectionReason")), 220)
            ));
            if (angles.size() == ANGLE_COUNT) {
                break;
            }
        }
        return angles;
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> map = safeMap(item);
            if (!map.isEmpty()) {
                maps.add(map);
            }
        }
        return maps;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<String> cleanStrings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(this::text).filter(value -> !value.isBlank()).limit(8).toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }
}
