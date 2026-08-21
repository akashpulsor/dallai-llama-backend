package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorCharacterCastMapping;
import com.dalai.llama.creator.domain.entity.CreatorIdea;
import com.dalai.llama.creator.domain.entity.CreatorProfile;
import com.dalai.llama.creator.domain.entity.CreatorProject;
import com.dalai.llama.creator.domain.entity.CreatorScriptCharacter;
import com.dalai.llama.creator.dto.request.CharacterCastMappingRequest;
import com.dalai.llama.creator.dto.response.CharacterCastMappingResponse;
import com.dalai.llama.creator.repository.CreatorCharacterCastMappingRepository;
import com.dalai.llama.creator.repository.CreatorIdeaRepository;
import com.dalai.llama.creator.repository.CreatorProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CharacterCastMappingService {

    private static final Duration REFERENCE_IMAGE_SIGNED_URL_TTL = Duration.ofDays(7);

    private final CreatorCharacterCastMappingRepository mappingRepository;
    private final CreatorIdeaRepository ideaRepository;
    private final CreatorProfileRepository profileRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ScriptStructureService scriptStructureService;
    private final CreatorProjectService projectService;
    private final AssetStorageService assetStorageService;

    public CharacterCastMappingService(
            CreatorCharacterCastMappingRepository mappingRepository,
            CreatorIdeaRepository ideaRepository,
            CreatorProfileRepository profileRepository,
            JdbcTemplate jdbcTemplate,
            ScriptStructureService scriptStructureService,
            CreatorProjectService projectService,
            AssetStorageService assetStorageService
    ) {
        this.mappingRepository = mappingRepository;
        this.ideaRepository = ideaRepository;
        this.profileRepository = profileRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.scriptStructureService = scriptStructureService;
        this.projectService = projectService;
        this.assetStorageService = assetStorageService;
    }

    @Transactional
    public CharacterCastMappingResponse listMappings(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorIdea lockedIdea = validateIdea(lockedIdeaId, safeTenantId, safeUserId, "Locked idea was not found.");
        CreatorIdea storyIdea = validateIdea(storyIdeaId, safeTenantId, safeUserId, "Story idea was not found.");
        UUID projectId = storyIdea.getProjectId() == null ? ensureProjectOnIdeas(lockedIdea, storyIdea) : storyIdea.getProjectId();
        List<CreatorCharacterCastMapping> mappings = mappingRepository
                .findByTenantIdAndUserIdAndLockedIdeaIdAndStoryIdeaIdOrderByCreatedAtAsc(safeTenantId, safeUserId, lockedIdeaId, storyIdeaId);
        return toResponse(lockedIdeaId, storyIdeaId, projectId, null, mappings);
    }

    @Transactional
    public CharacterCastMappingResponse saveMappings(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            CharacterCastMappingRequest request,
            String tenantId,
            String userId
    ) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        CreatorIdea lockedIdea = validateIdea(lockedIdeaId, safeTenantId, safeUserId, "Locked idea was not found.");
        CreatorIdea storyIdea = validateIdea(storyIdeaId, safeTenantId, safeUserId, "Story idea was not found.");
        if (request == null || request.mappings() == null || request.mappings().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Map at least one story character to a cast profile before confirming cast.");
        }
        UUID projectId = request == null || request.projectId() == null ? storyIdea.getProjectId() : request.projectId();
        if (projectId == null) {
            projectId = ensureProjectOnIdeas(lockedIdea, storyIdea);
        }
        UUID resolvedProjectId = projectId;
        UUID scriptId = request == null ? null : request.scriptId();
        OffsetDateTime now = OffsetDateTime.now();
        List<CharacterCastMappingRequest.CharacterCastMappingItem> uniqueMappings = dedupeMappingsByCharacterKey(request.mappings());
        if (uniqueMappings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Map at least one story character to a cast profile before confirming cast.");
        }

        List<CreatorCharacterCastMapping> saved = uniqueMappings.stream()
                .map(item -> {
                    CreatorProfile profile = item.castProfileId() == null
                            ? null
                            : profileRepository.findByIdAndTenantIdAndUserId(item.castProfileId(), safeTenantId, safeUserId)
                                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Selected cast profile was not found."));
                    CreatorCharacterCastMapping mapping = mappingRepository
                            .findByTenantIdAndUserIdAndLockedIdeaIdAndStoryIdeaIdAndCharacterKey(
                                    safeTenantId,
                                    safeUserId,
                                    lockedIdeaId,
                                    storyIdeaId,
                                    item.characterKey()
                            )
                            .orElseGet(() -> CreatorCharacterCastMapping.builder()
                                    .tenantId(safeTenantId)
                                    .userId(safeUserId)
                                    .lockedIdeaId(lockedIdeaId)
                                    .storyIdeaId(storyIdeaId)
                                    .characterKey(item.characterKey())
                                    .createdAt(now)
                                    .build());
                    mapping.setProjectId(resolvedProjectId);
                    mapping.setScriptId(scriptId);
                    mapping.setScriptCharacterId(resolveScriptCharacterId(scriptId, item));
                    mapping.setCharacterName(item.characterName());
                    mapping.setCharacterRole(item.characterRole());
                    mapping.setCastProfileId(profile == null ? item.castProfileId() : profile.getId());
                    mapping.setCastDisplayName(defaultString(item.castDisplayName(), profile == null ? "" : profile.getDisplayName()));
                    mapping.setCharacterPayload(new LinkedHashMap<>(item.characterPayload() == null ? Map.of() : item.characterPayload()));
                    mapping.setCastPayload(castPayloadFor(item.castPayload(), profile));
                    mapping.setUpdatedAt(now);
                    return mappingRepository.save(mapping);
                })
                .toList();

        attachMappingsToStoryIdea(storyIdea, saved, scriptId);
        linkProjectProfile(projectId, safeTenantId, safeUserId, firstCastProfileId(saved));
        return toResponse(lockedIdeaId, storyIdeaId, projectId, scriptId, saved);
    }

    private List<CharacterCastMappingRequest.CharacterCastMappingItem> dedupeMappingsByCharacterKey(
            List<CharacterCastMappingRequest.CharacterCastMappingItem> mappings
    ) {
        Map<String, CharacterCastMappingRequest.CharacterCastMappingItem> uniqueMappings = new LinkedHashMap<>();
        for (CharacterCastMappingRequest.CharacterCastMappingItem mapping : mappings == null ? List.<CharacterCastMappingRequest.CharacterCastMappingItem>of() : mappings) {
            if (mapping == null || mapping.characterKey() == null || mapping.characterKey().isBlank()) {
                continue;
            }
            uniqueMappings.put(mapping.characterKey(), mapping);
        }
        return uniqueMappings.values().stream().toList();
    }

    private CreatorIdea validateIdea(UUID ideaId, String tenantId, String userId, String message) {
        return ideaRepository.findByIdAndTenantIdAndUserId(ideaId, tenantId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, message));
    }

    private Map<String, Object> castPayloadFor(Map<String, Object> requestedPayload, CreatorProfile profile) {
        Map<String, Object> payload = new LinkedHashMap<>(requestedPayload == null ? Map.of() : requestedPayload);
        if (profile != null) {
            payload.putIfAbsent("id", profile.getId().toString());
            payload.putIfAbsent("name", profile.getDisplayName());
            payload.putIfAbsent("displayName", profile.getDisplayName());
            payload.putIfAbsent("roleInShort", profile.getRoleInShort());
            payload.putIfAbsent("attributes", profile.getAttributes() == null ? Map.of() : profile.getAttributes());
            // The profile only ever stores the durable bucket/objectKey pointer - sign a fresh
            // URL on every read (same reasoning as CreatorProfileService.toResponse()) so video
            // generation never gets handed an expired reference image URL.
            String referenceImageUrl = signedReferenceImageUrl(profile.getAttributes());
            if (!referenceImageUrl.isBlank()) {
                payload.put("referenceImageUrl", referenceImageUrl);
            }
        }
        return payload;
    }

    private String signedReferenceImageUrl(Map<String, Object> attributes) {
        if (attributes == null || !(attributes.get("referenceImage") instanceof Map<?, ?> referenceImage)) {
            return "";
        }
        Object bucketValue = referenceImage.get("bucket");
        Object objectKeyValue = referenceImage.get("objectKey");
        String bucket = bucketValue == null ? "" : String.valueOf(bucketValue).trim();
        String objectKey = objectKeyValue == null ? "" : String.valueOf(objectKeyValue).trim();
        if (bucket.isBlank() || objectKey.isBlank()) {
            return "";
        }
        return assetStorageService.signedUrl(bucket, objectKey, REFERENCE_IMAGE_SIGNED_URL_TTL);
    }

    private void attachMappingsToStoryIdea(CreatorIdea storyIdea, List<CreatorCharacterCastMapping> mappings, UUID scriptId) {
        Map<String, Object> context = new LinkedHashMap<>(storyIdea.getSelectionContext() == null ? Map.of() : storyIdea.getSelectionContext());
        context.put("characterCastMappings", mappings.stream().map(this::toMap).toList());
        if (scriptId != null) {
            context.put("characterCastScriptId", scriptId.toString());
        }
        context.put("characterCastMappedAt", OffsetDateTime.now().toString());
        storyIdea.setSelectionContext(context);
        storyIdea.setUpdatedAt(OffsetDateTime.now());
        ideaRepository.save(storyIdea);
    }

    private void linkProjectProfile(UUID projectId, String tenantId, String userId, UUID castProfileId) {
        if (projectId == null || castProfileId == null) {
            return;
        }
        jdbcTemplate.update(
                """
                update creator_projects
                   set selected_profile_id = ?,
                       status = 'CAST_MAPPED',
                       updated_at = now()
                 where id = ?
                   and tenant_id = ?
                   and user_id = ?
                """,
                castProfileId,
                projectId,
                tenantId,
                userId
        );
    }

    private UUID firstCastProfileId(List<CreatorCharacterCastMapping> mappings) {
        return mappings.stream()
                .map(CreatorCharacterCastMapping::getCastProfileId)
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);
    }

    private UUID ensureProjectOnIdeas(CreatorIdea lockedIdea, CreatorIdea storyIdea) {
        Map<String, Object> context = lockedIdea.getSelectionContext() == null ? Map.of() : lockedIdea.getSelectionContext();
        CreatorProject project = projectService.ensureProjectForLockedIdea(
                lockedIdea.getProjectId(),
                lockedIdea.getTenantId(),
                lockedIdea.getUserId(),
                lockedIdea.getTitle(),
                lockedIdea.getSummary(),
                lockedIdea.getSource(),
                lockedIdea.getTrendId(),
                stringValue(context.get("platformCode")),
                stringValue(context.get("categoryCode")),
                stringValue(context.get("countryCode")),
                stringValue(context.get("timeframe")),
                lockedIdea.getDurationSeconds(),
                context
        );
        if (lockedIdea.getProjectId() == null) {
            lockedIdea.setProjectId(project.getId());
            lockedIdea.setUpdatedAt(OffsetDateTime.now());
            ideaRepository.save(lockedIdea);
        }
        storyIdea.setProjectId(project.getId());
        storyIdea.setUpdatedAt(OffsetDateTime.now());
        ideaRepository.save(storyIdea);
        return project.getId();
    }

    private CharacterCastMappingResponse toResponse(
            UUID lockedIdeaId,
            UUID storyIdeaId,
            UUID projectId,
            UUID scriptId,
            List<CreatorCharacterCastMapping> mappings
    ) {
        UUID responseProjectId = projectId != null ? projectId : mappings.stream().map(CreatorCharacterCastMapping::getProjectId).filter(id -> id != null).findFirst().orElse(null);
        UUID responseScriptId = scriptId != null ? scriptId : mappings.stream().map(CreatorCharacterCastMapping::getScriptId).filter(id -> id != null).findFirst().orElse(null);
        return CharacterCastMappingResponse.builder()
                .lockedIdeaId(lockedIdeaId)
                .storyIdeaId(storyIdeaId)
                .projectId(responseProjectId)
                .scriptId(responseScriptId)
                .mappings(mappings.stream().map(this::toItem).toList())
                .build();
    }

    private CharacterCastMappingResponse.Item toItem(CreatorCharacterCastMapping mapping) {
        return CharacterCastMappingResponse.Item.builder()
                .id(mapping.getId())
                .scriptCharacterId(mapping.getScriptCharacterId())
                .characterKey(mapping.getCharacterKey())
                .characterName(mapping.getCharacterName())
                .characterRole(mapping.getCharacterRole())
                .castProfileId(mapping.getCastProfileId())
                .castDisplayName(mapping.getCastDisplayName())
                .characterPayload(mapping.getCharacterPayload())
                .castPayload(mapping.getCastPayload())
                .createdAt(mapping.getCreatedAt())
                .updatedAt(mapping.getUpdatedAt())
                .build();
    }

    private Map<String, Object> toMap(CreatorCharacterCastMapping mapping) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", mapping.getId() == null ? null : mapping.getId().toString());
        value.put("scriptCharacterId", mapping.getScriptCharacterId() == null ? null : mapping.getScriptCharacterId().toString());
        value.put("characterKey", mapping.getCharacterKey());
        value.put("characterName", mapping.getCharacterName());
        value.put("characterRole", mapping.getCharacterRole());
        value.put("castProfileId", mapping.getCastProfileId() == null ? null : mapping.getCastProfileId().toString());
        value.put("castDisplayName", mapping.getCastDisplayName());
        value.put("characterPayload", mapping.getCharacterPayload());
        value.put("castPayload", mapping.getCastPayload());
        return value;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private UUID resolveScriptCharacterId(UUID scriptId, CharacterCastMappingRequest.CharacterCastMappingItem item) {
        if (item.scriptCharacterId() != null) {
            return item.scriptCharacterId();
        }
        return scriptStructureService.findScriptCharacter(scriptId, item.characterKey())
                .map(CreatorScriptCharacter::getId)
                .orElse(null);
    }
}
