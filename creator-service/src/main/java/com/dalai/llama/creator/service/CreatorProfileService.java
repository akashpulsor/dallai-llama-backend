package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorProfile;
import com.dalai.llama.creator.dto.request.CastProfileRequest;
import com.dalai.llama.creator.dto.response.CastProfileResponse;
import com.dalai.llama.creator.repository.CreatorProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CreatorProfileService {

    private static final long MAX_REFERENCE_IMAGE_BYTES = 15L * 1024L * 1024L;
    private static final Duration REFERENCE_IMAGE_SIGNED_URL_TTL = Duration.ofDays(7);
    private static final Set<String> SUPPORTED_REFERENCE_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final CreatorProfileRepository profileRepository;
    private final AssetStorageService assetStorageService;

    public CreatorProfileService(CreatorProfileRepository profileRepository, AssetStorageService assetStorageService) {
        this.profileRepository = profileRepository;
        this.assetStorageService = assetStorageService;
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

    @Transactional
    public CastProfileResponse uploadReferenceImage(UUID profileId, MultipartFile file, String tenantId, String userId) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorProfile profile = profileRepository
                .findByIdAndTenantIdAndUserId(profileId, safeTenantId, safeUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cast profile was not found."));
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a reference photo to upload.");
        }
        if (file.getSize() > MAX_REFERENCE_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Reference photo must be 15 MB or smaller.");
        }
        String contentType = normalizedImageContentType(file.getContentType());
        if (!SUPPORTED_REFERENCE_IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Reference photo must be JPG, PNG, or WebP.");
        }

        String objectKey = "%s/%s/cast-profiles/%s/reference%s".formatted(
                safePath(safeTenantId), safePath(safeUserId), profileId, extensionFor(contentType)
        );
        AssetStorageService.StoredObject stored;
        try (InputStream inputStream = file.getInputStream()) {
            stored = assetStorageService.uploadCreatorAssetFromStream(objectKey, inputStream, contentType, REFERENCE_IMAGE_SIGNED_URL_TTL);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded reference photo.", ex);
        }

        // Store the durable bucket/objectKey pointer, not the signed URL itself - signed URLs
        // expire and this codebase already tracks the exact bug class that causes (a combined
        // video asset going stale). toResponse() re-signs a fresh URL on every read instead.
        Map<String, Object> attributes = new LinkedHashMap<>(profile.getAttributes() == null ? Map.of() : profile.getAttributes());
        Map<String, Object> referenceImage = new LinkedHashMap<>();
        referenceImage.put("bucket", stored.bucket());
        referenceImage.put("objectKey", stored.objectKey());
        referenceImage.put("contentType", stored.contentType());
        attributes.put("referenceImage", referenceImage);
        profile.setAttributes(attributes);
        profile.setUpdatedAt(OffsetDateTime.now());
        return toResponse(profileRepository.save(profile));
    }

    private String normalizedImageContentType(String contentType) {
        String normalized = defaultString(contentType, "").toLowerCase(Locale.ROOT).trim();
        int separator = normalized.indexOf(';');
        if (separator >= 0) {
            normalized = normalized.substring(0, separator).trim();
        }
        return "image/jpg".equals(normalized) ? "image/jpeg" : normalized;
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private String safePath(String value) {
        String safe = defaultString(value, "unknown")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+|[.-]+$", "");
        return safe.isBlank() ? "unknown" : safe;
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
        // referenceImage only ever stores the durable bucket/objectKey pointer - sign a fresh
        // URL on every read so it never goes stale, same pattern as the scene-asset endpoints.
        if (attributes.get("referenceImage") instanceof Map<?, ?> referenceImage) {
            String bucket = stringValue(referenceImage.get("bucket"));
            String objectKey = stringValue(referenceImage.get("objectKey"));
            if (!bucket.isBlank() && !objectKey.isBlank()) {
                attributes.put("referenceImageUrl", assetStorageService.signedUrl(bucket, objectKey, REFERENCE_IMAGE_SIGNED_URL_TTL));
            }
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
