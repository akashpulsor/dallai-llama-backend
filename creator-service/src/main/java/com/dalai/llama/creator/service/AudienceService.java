package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorAudience;
import com.dalai.llama.creator.dto.request.AudienceDecisionRequest;
import com.dalai.llama.creator.dto.response.AudienceResponse;
import com.dalai.llama.creator.repository.CreatorAudienceRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AudienceService {

    private final CreatorAudienceRepository audienceRepository;
    private final EntityManager entityManager;

    public AudienceService(CreatorAudienceRepository audienceRepository, EntityManager entityManager) {
        this.audienceRepository = audienceRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public AudienceResponse suggestAudience(AudienceDecisionRequest request, String tenantId, String userId) {
        AudienceDecisionRequest safeRequest = request == null ? new AudienceDecisionRequest() : request;
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        AudienceProfile profile = deriveAudienceProfile(safeRequest);

        CreatorAudience audience = CreatorAudience.builder()
                .tenantId(safeTenantId)
                .userId(safeUserId)
                .projectId(parseUuid(safeRequest.getProjectId()))
                .title(profile.title())
                .demographics(profile.demographics())
                .interests(profile.interests())
                .psychographics(profile.psychographics())
                .contentPreference(profile.contentPreference())
                .aiSuggested(true)
                .confirmed(false)
                .build();

        return toResponse(audienceRepository.save(audience));
    }

    @Transactional
    public AudienceResponse confirmAudience(AudienceDecisionRequest request, String tenantId, String userId) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audience payload is required.");
        }

        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        UUID audienceId = parseUuid(request.getId());
        CreatorAudience audience = audienceId == null
                ? newAudience(safeTenantId, safeUserId, request)
                : audienceRepository.findByIdAndTenantIdAndUserId(audienceId, safeTenantId, safeUserId)
                    .orElseGet(() -> newAudience(safeTenantId, safeUserId, request));

        AudienceProfile fallbackProfile = deriveAudienceProfile(request);
        Map<String, Object> demographics = mergeMaps(audience.getDemographics(), request.getDemographics());
        putIfPresent(demographics, "gender", defaultString(request.getGender(), stringValue(demographics.get("gender"))));
        putIfPresent(demographics, "ageGroup", defaultString(request.getAgeGroup(), stringValue(demographics.get("ageGroup"))));
        putIfPresent(demographics, "location", defaultString(request.getLocation(), stringValue(demographics.get("location"))));
        putIfPresent(demographics, "countryCode", request.getCountryCode());

        Map<String, Object> psychographics = mergeMaps(audience.getPsychographics(), request.getPsychographics());
        putIfPresent(psychographics, "description", defaultString(request.getDescription(), stringValue(psychographics.get("description"))));
        psychographics.put("latestDecisionContext", sourceContextFrom(request));
        psychographics.put("confirmedAt", OffsetDateTime.now().toString());

        List<String> interests = cleanList(request.getInterests());
        if (interests.isEmpty()) {
            interests = audience.getInterests() == null || audience.getInterests().isEmpty()
                    ? fallbackProfile.interests()
                    : cleanList(audience.getInterests());
        }

        String title = defaultString(request.getTitle(), audience.getTitle());
        if (title.isBlank()) {
            title = fallbackProfile.title();
        }
        if (title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audience title is required.");
        }

        audience.setProjectId(parseUuid(request.getProjectId()) == null ? audience.getProjectId() : parseUuid(request.getProjectId()));
        audience.setTitle(title);
        audience.setDemographics(demographics);
        audience.setInterests(interests);
        audience.setPsychographics(psychographics);
        audience.setContentPreference(defaultString(request.getContentPreference(), defaultString(audience.getContentPreference(), fallbackProfile.contentPreference())));
        audience.setConfirmed(true);
        audience.setUpdatedAt(OffsetDateTime.now());

        CreatorAudience saved = audienceRepository.save(audience);
        linkProjectToAudience(saved.getProjectId(), saved.getId(), safeTenantId, safeUserId);
        return toResponse(saved);
    }

    private CreatorAudience newAudience(String tenantId, String userId, AudienceDecisionRequest request) {
        return CreatorAudience.builder()
                .tenantId(tenantId)
                .userId(userId)
                .projectId(parseUuid(request.getProjectId()))
                .aiSuggested(false)
                .confirmed(false)
                .build();
    }

    private AudienceProfile deriveAudienceProfile(AudienceDecisionRequest request) {
        String text = sourceTextFrom(request);
        String countryName = countryName(defaultString(request.getCountryCode(), ""), request.getLocation());
        String location = defaultString(request.getLocation(), countryName);
        String category = defaultString(request.getCategoryCode(), "creator").toLowerCase(Locale.ROOT);

        if (matchesAny(text, "saas", "bahu", "wife", "family", "couple", "neighbor", "comedy")) {
            return profile(
                    "Family comedy viewers in " + location,
                    "All",
                    "22 - 45",
                    location,
                    List.of("Lifestyle", "Family", "Comedy", "Relatable Humor"),
                    "Funny & Honest",
                    "Viewers who instantly understand household tension, character reactions, and small family punchlines.",
                    request
            );
        }
        if (matchesAny(text, "study", "exam", "student", "focus", "late night", "productivity") || category.contains("study")) {
            return profile(
                    "Students and focus builders in " + location,
                    "All",
                    "18 - 24",
                    location,
                    List.of("Study", "Productivity", "Self Improvement", "Focus"),
                    "Educational",
                    "Viewers looking for realistic focus, discipline, exam motivation, and routines they can copy.",
                    request
            );
        }
        if (matchesAny(text, "food", "meal", "protein", "recipe", "kitchen", "lunch", "dinner") || category.contains("food")) {
            return profile(
                    "Practical food viewers in " + location,
                    "All",
                    "22 - 35",
                    location,
                    List.of("Food", "Health", "Kitchen Shortcuts", "Protein Meals"),
                    "Educational",
                    "Viewers who save practical meal ideas, quick recipes, and realistic food routines.",
                    request
            );
        }
        if (matchesAny(text, "skin", "beauty", "glow", "routine", "makeup", "hair") || category.contains("beauty")) {
            return profile(
                    "Beauty routine viewers in " + location,
                    "Female",
                    "18 - 30",
                    location,
                    List.of("Beauty", "Skin Care", "Confidence", "Before / After"),
                    "Before / After",
                    "Viewers who respond to visible change, honest routines, and low-effort glow-up storytelling.",
                    request
            );
        }
        if (matchesAny(text, "ai", "software", "tech", "tool", "startup", "business") || category.contains("tech") || category.contains("business")) {
            return profile(
                    "Ambitious digital builders in " + location,
                    "All",
                    "22 - 40",
                    location,
                    List.of("Tech & AI", "Business", "Creator Growth", "Productivity"),
                    "Insightful & Practical",
                    "Viewers who want clear tools, founder lessons, workflow improvements, and credible proof.",
                    request
            );
        }
        return profile(
                "Women 22 - 35 in " + location,
                "Female",
                "22 - 35",
                location,
                List.of("Fitness", "Health", "Self Improvement", "Confidence"),
                "Motivational & Relatable",
                "Viewers who respond to beginner-friendly progress, honest struggle, habit change, and emotional payoff.",
                request
        );
    }

    private AudienceProfile profile(
            String title,
            String gender,
            String ageGroup,
            String location,
            List<String> interests,
            String contentPreference,
            String description,
            AudienceDecisionRequest request
    ) {
        Map<String, Object> demographics = new LinkedHashMap<>();
        demographics.put("gender", gender);
        demographics.put("ageGroup", ageGroup);
        demographics.put("location", location);
        putIfPresent(demographics, "countryCode", request.getCountryCode());

        Map<String, Object> psychographics = new LinkedHashMap<>();
        psychographics.put("description", description);
        psychographics.put("primaryMotivation", primaryMotivationFor(description));
        psychographics.put("contentTrigger", contentPreference);
        psychographics.put("aiDecisionBasis", "Derived from story script, screenplay metadata, cast mapping, and optional brand context.");
        psychographics.put("scriptSignals", scriptSignalsFrom(request));
        psychographics.put("brandContext", nullToEmptyMap(request.getBrandContext()));
        psychographics.put("castContext", castContextFrom(request));
        psychographics.put("sourceContext", sourceContextFrom(request));

        return new AudienceProfile(title, demographics, interests, psychographics, contentPreference);
    }

    private Map<String, Object> scriptSignalsFrom(AudienceDecisionRequest request) {
        Map<String, Object> signals = new LinkedHashMap<>();
        copyKeys(signals, request.getStoryScriptJson(), List.of("projectTitle", "logline", "storyline", "emotionalArc", "hookStrategy", "duration"));
        copyKeys(signals, request.getScriptJson(), List.of("projectTitle", "pacingStyle", "emotionalArc", "hookStrategy", "totalShots", "duration"));
        putIfPresent(signals, "idea", request.getIdea());
        return signals;
    }

    private Map<String, Object> castContextFrom(AudienceDecisionRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> castPlan = nullToEmptyMap(request.getCastPlan());
        Object actors = castPlan.get("actors");
        context.put("actorCount", actors instanceof List<?> list ? list.size() : 0);
        context.put("mappedCharacters", request.getCharacterCastMappings() == null ? List.of() : request.getCharacterCastMappings());
        return context;
    }

    private Map<String, Object> sourceContextFrom(AudienceDecisionRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        putIfPresent(context, "projectId", request.getProjectId());
        putIfPresent(context, "lockedIdeaId", request.getLockedIdeaId());
        putIfPresent(context, "storyIdeaId", request.getStoryIdeaId());
        putIfPresent(context, "scriptId", request.getScriptId());
        putIfPresent(context, "trendId", request.getTrendId());
        putIfPresent(context, "categoryCode", request.getCategoryCode());
        putIfPresent(context, "countryCode", request.getCountryCode());
        context.put("trend", nullToEmptyMap(request.getTrend()));
        context.put("selectedIdea", nullToEmptyMap(request.getSelectedIdea()));
        context.put("extraContext", nullToEmptyMap(request.getContext()));
        return context;
    }

    private void linkProjectToAudience(UUID projectId, UUID audienceId, String tenantId, String userId) {
        if (projectId == null || audienceId == null) {
            return;
        }
        entityManager.createNativeQuery("""
                UPDATE creator_projects
                SET selected_audience_id = :audienceId,
                    status = 'AUDIENCE_CONFIRMED',
                    updated_at = now()
                WHERE id = :projectId AND tenant_id = :tenantId AND user_id = :userId
                """)
                .setParameter("audienceId", audienceId)
                .setParameter("projectId", projectId)
                .setParameter("tenantId", tenantId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    private AudienceResponse toResponse(CreatorAudience audience) {
        Map<String, Object> demographics = nullToEmptyMap(audience.getDemographics());
        Map<String, Object> psychographics = nullToEmptyMap(audience.getPsychographics());
        return AudienceResponse.builder()
                .id(audience.getId())
                .projectId(audience.getProjectId())
                .title(audience.getTitle())
                .description(stringValue(psychographics.get("description")))
                .gender(stringValue(demographics.get("gender")))
                .ageGroup(stringValue(demographics.get("ageGroup")))
                .location(stringValue(demographics.get("location")))
                .interests(cleanList(audience.getInterests()))
                .contentPreference(audience.getContentPreference())
                .aiSuggested(audience.isAiSuggested())
                .confirmed(audience.isConfirmed())
                .demographics(demographics)
                .psychographics(psychographics)
                .createdAt(audience.getCreatedAt())
                .updatedAt(audience.getUpdatedAt())
                .build();
    }

    private void copyKeys(Map<String, Object> target, Map<String, Object> source, List<String> keys) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                target.put(key, source.get(key));
            }
        }
    }

    private Map<String, Object> mergeMaps(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> merged = new LinkedHashMap<>(nullToEmptyMap(base));
        if (overlay != null) {
            merged.putAll(overlay);
        }
        return merged;
    }

    private Map<String, Object> nullToEmptyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value != null) {
            map.put(key, value);
        }
    }

    private String sourceTextFrom(AudienceDecisionRequest request) {
        return String.join(" ",
                defaultString(request.getIdea(), ""),
                mapText(request.getSelectedIdea(), "title"),
                mapText(request.getSelectedIdea(), "description"),
                mapText(request.getTrend(), "title"),
                mapText(request.getTrend(), "summary"),
                mapText(request.getStoryScriptJson(), "projectTitle"),
                mapText(request.getStoryScriptJson(), "storyline"),
                mapText(request.getStoryScriptJson(), "emotionalArc"),
                mapText(request.getScriptJson(), "projectTitle"),
                mapText(request.getScriptJson(), "emotionalArc"),
                mapText(request.getScriptJson(), "hookStrategy"),
                defaultString(request.getCategoryCode(), "")
        ).toLowerCase(Locale.ROOT);
    }

    private String mapText(Map<String, Object> source, String key) {
        return source == null ? "" : stringValue(source.get(key));
    }

    private boolean matchesAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String primaryMotivationFor(String description) {
        String lower = description.toLowerCase(Locale.ROOT);
        if (lower.contains("household") || lower.contains("family")) {
            return "Feel seen through relatable character reactions.";
        }
        if (lower.contains("study") || lower.contains("focus")) {
            return "Find practical discipline and emotional support.";
        }
        if (lower.contains("meal") || lower.contains("recipe")) {
            return "Save useful ideas that can be tried quickly.";
        }
        if (lower.contains("beauty") || lower.contains("glow")) {
            return "See believable improvement without complex routines.";
        }
        return "Believe a small beginner action can create visible progress.";
    }

    private String countryName(String countryCode, String fallbackLocation) {
        if (fallbackLocation != null && !fallbackLocation.isBlank()) {
            return fallbackLocation;
        }
        return switch (countryCode.toUpperCase(Locale.ROOT)) {
            case "US" -> "United States";
            case "GB", "UK" -> "United Kingdom";
            case "AE" -> "UAE";
            case "IN" -> "India";
            default -> "India";
        };
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record AudienceProfile(
            String title,
            Map<String, Object> demographics,
            List<String> interests,
            Map<String, Object> psychographics,
            String contentPreference
    ) {
    }
}
