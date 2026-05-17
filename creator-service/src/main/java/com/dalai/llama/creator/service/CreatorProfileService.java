package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorProfile;
import com.dalai.llama.creator.dto.request.CastProfileRequest;
import com.dalai.llama.creator.dto.response.CastProfileResponse;
import com.dalai.llama.creator.repository.CreatorProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CreatorProfileService {

    private final CreatorProfileRepository profileRepository;

    public CreatorProfileService(CreatorProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @Transactional
    public List<CastProfileResponse> listProfiles(UUID projectId, String tenantId, String userId) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        List<CreatorProfile> profiles = profileRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(safeTenantId, safeUserId);
        if (profiles.isEmpty()) {
            profiles = seedDefaultProfiles(safeTenantId, safeUserId);
        }
        return profiles.stream()
                .filter(profile -> projectId == null || profile.getProjectId() == null || projectId.equals(profile.getProjectId()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CastProfileResponse createProfile(CastProfileRequest request, String tenantId, String userId) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        String displayName = displayNameFrom(request);
        if (displayName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cast profile name is required.");
        }

        CreatorProfile profile = CreatorProfile.builder()
                .tenantId(safeTenantId)
                .userId(safeUserId)
                .projectId(request == null ? null : request.projectId())
                .displayName(displayName)
                .roleInShort(defaultString(request == null ? null : request.roleInShort(), "Main Actor"))
                .attributes(attributesFrom(request, null))
                .confirmed(request == null || request.confirmed() == null || request.confirmed())
                .build();
        return toResponse(profileRepository.save(profile));
    }

    @Transactional
    public CastProfileResponse updateProfile(UUID profileId, CastProfileRequest request, String tenantId, String userId) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorProfile profile = profileRepository
                .findByIdAndTenantIdAndUserId(profileId, safeTenantId, safeUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cast profile was not found."));

        String displayName = displayNameFrom(request);
        if (!displayName.isBlank()) {
            profile.setDisplayName(displayName);
        }
        if (request != null && request.projectId() != null) {
            profile.setProjectId(request.projectId());
        }
        if (request != null && request.roleInShort() != null && !request.roleInShort().isBlank()) {
            profile.setRoleInShort(request.roleInShort());
        }
        if (request != null && request.confirmed() != null) {
            profile.setConfirmed(request.confirmed());
        }
        profile.setAttributes(attributesFrom(request, profile.getAttributes()));
        profile.setUpdatedAt(OffsetDateTime.now());
        return toResponse(profileRepository.save(profile));
    }

    private List<CreatorProfile> seedDefaultProfiles(String tenantId, String userId) {
        List<CreatorProfile> defaults = List.of(
                defaultProfile(tenantId, userId, "Priya", "Main Actor", 27, "Female", List.of("Relatable", "Soft Spoken", "Determined"), "Casual Gym Wear", "Shy", "Everyday casual outfit, natural face, expressive eyes.", "Beginner creator who can carry emotional hesitation and small-win payoff."),
                defaultProfile(tenantId, userId, "Ananya", "Main Actor", 24, "Female", List.of("Confident", "Luxury Vibe", "Energetic"), "Athleisure", "Confident", "Polished athleisure styling with direct-camera comfort.", "Confident creator profile for aspirational, glow-up, and bold transformation shorts."),
                defaultProfile(tenantId, userId, "Meera", "Supporting Actor", 31, "Female", List.of("Funny", "Relatable", "Beginner Creator"), "Home Setup", "Somewhat Comfortable", "Warm home-wear look with expressive reaction timing.", "Grounded family/comedy performer who works well as a friend, sibling, or reaction character."),
                defaultProfile(tenantId, userId, "Aman", "Supporting Actor", 29, "Male", List.of("Funny", "Relatable", "Energetic"), "Office Casual", "Confident", "Casual office styling with quick comic expression.", "High-energy supporting actor for friend, partner, coach, or punchline roles.")
        );
        return profileRepository.saveAll(defaults);
    }

    private CreatorProfile defaultProfile(
            String tenantId,
            String userId,
            String name,
            String role,
            Integer age,
            String gender,
            List<String> vibe,
            String style,
            String cameraConfidence,
            String look,
            String profileText
    ) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("age", age);
        attributes.put("gender", gender);
        attributes.put("vibe", vibe);
        attributes.put("vibes", vibe);
        attributes.put("style", style);
        attributes.put("cameraConfidence", cameraConfidence);
        attributes.put("look", look);
        attributes.put("profile", profileText);
        return CreatorProfile.builder()
                .tenantId(tenantId)
                .userId(userId)
                .displayName(name)
                .roleInShort(role)
                .attributes(attributes)
                .confirmed(true)
                .build();
    }

    private CastProfileResponse toResponse(CreatorProfile profile) {
        Map<String, Object> attributes = profile.getAttributes() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(profile.getAttributes());
        List<String> vibe = stringList(attributes.get("vibe"));
        if (vibe.isEmpty()) {
            vibe = stringList(attributes.get("vibes"));
        }
        return CastProfileResponse.builder()
                .id(profile.getId())
                .projectId(profile.getProjectId())
                .name(profile.getDisplayName())
                .displayName(profile.getDisplayName())
                .roleInShort(profile.getRoleInShort())
                .age(integerValue(attributes.get("age")))
                .gender(stringValue(attributes.get("gender")))
                .vibe(vibe)
                .vibes(vibe)
                .style(stringValue(attributes.get("style")))
                .cameraConfidence(stringValue(attributes.get("cameraConfidence")))
                .look(stringValue(attributes.get("look")))
                .profile(stringValue(attributes.get("profile")))
                .notes(stringValue(attributes.get("notes")))
                .confirmed(profile.isConfirmed())
                .attributes(attributes)
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }

    private Map<String, Object> attributesFrom(CastProfileRequest request, Map<String, Object> existing) {
        Map<String, Object> attributes = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        if (request == null) {
            return attributes;
        }
        if (request.attributes() != null) {
            attributes.putAll(request.attributes());
        }
        putIfPresent(attributes, "age", request.age());
        putIfPresent(attributes, "gender", request.gender());
        List<String> vibe = request.vibe() == null || request.vibe().isEmpty() ? request.vibes() : request.vibe();
        if (vibe != null) {
            attributes.put("vibe", cleanList(vibe));
            attributes.put("vibes", cleanList(vibe));
        }
        putIfPresent(attributes, "style", request.style());
        putIfPresent(attributes, "cameraConfidence", request.cameraConfidence());
        putIfPresent(attributes, "look", request.look());
        putIfPresent(attributes, "profile", request.profile());
        putIfPresent(attributes, "notes", request.notes());
        return attributes;
    }

    private void putIfPresent(Map<String, Object> attributes, String key, Object value) {
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private String displayNameFrom(CastProfileRequest request) {
        if (request == null) {
            return "";
        }
        return defaultString(defaultString(request.displayName(), request.name()), "").trim();
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> cleaned = new ArrayList<>();
            for (Object item : list) {
                String text = stringValue(item);
                if (!text.isBlank()) {
                    cleaned.add(text);
                }
            }
            return cleaned;
        }
        String text = stringValue(value);
        if (text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
