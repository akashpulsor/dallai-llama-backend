package com.dalai.llama.creator.service;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.dto.response.PostProductionProjectResponse;
import com.dalai.llama.creator.dto.response.PostProductionShotResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CreatorPostProductionService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 60;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AssetStorageService assetStorageService;
    private final CreatorProperties properties;

    public CreatorPostProductionService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AssetStorageService assetStorageService,
            CreatorProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.assetStorageService = assetStorageService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<PostProductionProjectResponse> listShotReadyProjects(String tenantId, String userId, Integer limit) {
        String safeTenantId = defaultString(tenantId, "unknown");
        String safeUserId = defaultString(userId, "anonymous");
        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit == null ? DEFAULT_LIMIT : limit));

        List<ProjectRow> rows = jdbcTemplate.query(
                """
                with latest_scripts as (
                    select distinct on (s.project_id)
                        s.id as script_id,
                        s.project_id,
                        s.story_idea_id,
                        s.title as script_title,
                        s.status as script_status,
                        s.duration_seconds,
                        s.screen_type,
                        s.total_shots,
                        s.updated_at as script_updated_at
                    from creator_scripts s
                    where s.tenant_id = ?
                      and s.user_id = ?
                      and s.project_id is not null
                      and (
                        exists (select 1 from creator_script_shot_plans pp where pp.script_id = s.id)
                        or exists (select 1 from creator_script_shots ss where ss.script_id = s.id)
                        or jsonb_array_length(coalesce(s.shots, '[]'::jsonb)) > 0
                      )
                    order by s.project_id, s.updated_at desc
                )
                select
                    ls.script_id,
                    ls.project_id,
                    ls.story_idea_id,
                    coalesce(nullif(p.preferences ->> 'title', ''), nullif(p.preferences ->> 'briefTitle', ''), ls.script_title, 'Creator project') as project_title,
                    coalesce(p.status, ls.script_status, 'GENERATED') as status,
                    ls.duration_seconds,
                    ls.screen_type,
                    ls.total_shots,
                    greatest(coalesce(p.updated_at, ls.script_updated_at), ls.script_updated_at) as updated_at
                from latest_scripts ls
                left join creator_projects p
                  on p.id = ls.project_id
                 and p.tenant_id = ?
                 and p.user_id = ?
                order by greatest(coalesce(p.updated_at, ls.script_updated_at), ls.script_updated_at) desc
                limit ?
                """,
                (rs, rowNum) -> new ProjectRow(
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("script_id", UUID.class),
                        rs.getObject("story_idea_id", UUID.class),
                        rs.getString("project_title"),
                        rs.getString("status"),
                        intValue(rs.getObject("duration_seconds"), null),
                        rs.getString("screen_type"),
                        intValue(rs.getObject("total_shots"), null),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ),
                safeTenantId,
                safeUserId,
                safeTenantId,
                safeUserId,
                safeLimit
        );

        List<PostProductionProjectResponse> responses = new ArrayList<>();
        for (ProjectRow row : rows) {
            List<PostProductionShotResponse> shots = listShots(row.scriptId(), row.projectId(), safeTenantId, safeUserId);
            if (shots.isEmpty()) {
                continue;
            }
            responses.add(new PostProductionProjectResponse(
                    row.projectId(),
                    row.scriptId(),
                    row.storyIdeaId(),
                    defaultString(row.title(), "Creator project"),
                    defaultString(row.status(), "GENERATED"),
                    row.durationSeconds(),
                    defaultString(row.screenType(), "vertical"),
                    row.totalShots() == null || row.totalShots() <= 0 ? shots.size() : row.totalShots(),
                    shots,
                    row.updatedAt()
            ));
        }
        return responses;
    }

    private List<PostProductionShotResponse> listShots(UUID scriptId, UUID projectId, String tenantId, String userId) {
        Map<Integer, AssetGroup> assetsByShot = loadAssetsByShot(scriptId, tenantId, userId);
        return jdbcTemplate.query(
                """
                with shot_numbers as (
                    select shot_number from creator_script_shots where script_id = ?
                    union
                    select shot_number from creator_script_shot_plans where script_id = ?
                )
                select
                    n.shot_number,
                    ss.title,
                    ss.start_time,
                    ss.end_time,
                    ss.duration_seconds,
                    ss.shot_type,
                    coalesce(ss.shot_payload ->> 'cameraAngle', ss.shot_payload ->> 'camera_angle') as camera_angle,
                    coalesce(ss.shot_payload ->> 'cameraMovement', ss.shot_payload ->> 'camera_movement') as camera_movement,
                    coalesce(ss.shot_payload ->> 'lensSuggestion', ss.shot_payload ->> 'lens_suggestion') as lens_suggestion,
                    ss.shot_payload::text as shot_payload_json,
                    ss.updated_at as shot_updated_at,
                    pp.storyboard_tag::text as storyboard_tag_json,
                    pp.lighting_build_sheet_tag::text as lighting_tag_json,
                    pp.camera_plan_sheet_tag::text as camera_tag_json,
                    pp.updated_at as plan_updated_at
                from shot_numbers n
                left join creator_script_shots ss
                  on ss.script_id = ?
                 and ss.shot_number = n.shot_number
                left join creator_script_shot_plans pp
                  on pp.script_id = ?
                 and pp.shot_number = n.shot_number
                order by n.shot_number asc
                """,
                (rs, rowNum) -> {
                    int shotNumber = intValue(rs.getObject("shot_number"), rowNum + 1);
                    Map<String, Object> shotPayload = jsonMap(rs.getString("shot_payload_json"));
                    Map<String, Object> storyboardTag = jsonMap(rs.getString("storyboard_tag_json"));
                    Map<String, Object> lightingTag = jsonMap(rs.getString("lighting_tag_json"));
                    Map<String, Object> cameraTag = jsonMap(rs.getString("camera_tag_json"));
                    AssetGroup assets = assetsByShot.getOrDefault(shotNumber, new AssetGroup());
                    return new PostProductionShotResponse(
                            scriptId,
                            projectId,
                            shotNumber,
                            firstText(
                                    rs.getString("title"),
                                    stringValue(storyboardTag.get("shotTitle")),
                                    stringValue(cameraTag.get("shotTitle")),
                                    "Shot %02d".formatted(shotNumber)
                            ),
                            doubleValue(rs.getObject("start_time")),
                            doubleValue(rs.getObject("end_time")),
                            doubleValue(rs.getObject("duration_seconds")),
                            firstText(rs.getString("shot_type"), stringValue(storyboardTag.get("shotType")), stringValue(cameraTag.get("shotType"))),
                            firstText(rs.getString("camera_angle"), stringValue(storyboardTag.get("cameraAngle")), stringValue(cameraTag.get("cameraAngle"))),
                            firstText(rs.getString("camera_movement"), stringValue(storyboardTag.get("cameraMovement")), stringValue(cameraTag.get("cameraMovement"))),
                            firstText(rs.getString("lens_suggestion"), stringValue(storyboardTag.get("lensSuggestion")), stringValue(cameraTag.get("lensSuggestion"))),
                            assets.storyboardUrl(),
                            assets.lightingUrl(),
                            assets.cameraPlanUrl(),
                            assets.storyboardAssetId(),
                            assets.lightingAssetId(),
                            assets.cameraPlanAssetId(),
                            shotPayload,
                            storyboardTag,
                            lightingTag,
                            cameraTag,
                            maxTime(
                                    rs.getObject("shot_updated_at", OffsetDateTime.class),
                                    rs.getObject("plan_updated_at", OffsetDateTime.class),
                                    assets.updatedAt()
                            )
                    );
                },
                scriptId,
                scriptId,
                scriptId,
                scriptId
        );
    }

    private Map<Integer, AssetGroup> loadAssetsByShot(UUID scriptId, String tenantId, String userId) {
        Map<Integer, AssetGroup> assetsByShot = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                select id, asset_type, bucket, object_key, public_url, metadata::text as metadata_json, created_at
                from creator_assets
                where tenant_id = ?
                  and user_id = ?
                  and metadata ->> 'scriptId' = cast(? as text)
                  and jsonb_exists(metadata, 'shotNumber')
                order by created_at desc
                """,
                rs -> {
                    Map<String, Object> metadata = jsonMap(rs.getString("metadata_json"));
                    Integer shotNumber = intValue(metadata.get("shotNumber"), null);
                    if (shotNumber == null) {
                        return;
                    }
                    AssetGroup group = assetsByShot.computeIfAbsent(shotNumber, ignored -> new AssetGroup());
                    AssetRef asset = new AssetRef(
                            rs.getObject("id", UUID.class),
                            signedUrlFor(rs.getString("bucket"), rs.getString("object_key"), rs.getString("public_url")),
                            rs.getObject("created_at", OffsetDateTime.class)
                    );
                    String kind = imageKind(rs.getString("asset_type"), stringValue(metadata.get("imageKind")), rs.getString("object_key"));
                    if ("lighting".equals(kind) && group.lightingAssetId == null) {
                        group.lightingAssetId = asset.assetId();
                        group.lightingUrl = asset.url();
                    } else if ("dp".equals(kind) && group.cameraPlanAssetId == null) {
                        group.cameraPlanAssetId = asset.assetId();
                        group.cameraPlanUrl = asset.url();
                    } else if (group.storyboardAssetId == null) {
                        group.storyboardAssetId = asset.assetId();
                        group.storyboardUrl = asset.url();
                    }
                    group.updatedAt = maxTime(group.updatedAt, asset.updatedAt());
                },
                tenantId,
                userId,
                scriptId
        );
        return assetsByShot;
    }

    private String signedUrlFor(String bucket, String objectKey, String publicUrl) {
        if (bucket == null || bucket.isBlank() || objectKey == null || objectKey.isBlank()) {
            return publicUrl;
        }
        try {
            return assetStorageService.signedUrl(bucket, objectKey, signedUrlTtl());
        } catch (RuntimeException ex) {
            return publicUrl;
        }
    }

    private Duration signedUrlTtl() {
        long seconds = properties.getStorage().getSignedUrlTtlSeconds();
        return Duration.ofSeconds(Math.max(300, Math.min(604800, seconds <= 0 ? 3600 : seconds)));
    }

    private Map<String, Object> jsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(value, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String imageKind(String assetType, String imageKind, String objectKey) {
        String value = "%s %s %s".formatted(
                defaultString(assetType, ""),
                defaultString(imageKind, ""),
                defaultString(objectKey, "")
        ).toLowerCase();
        if (value.contains("lighting") || value.contains("light")) {
            return "lighting";
        }
        if (value.contains("camera") || value.contains("dp")) {
            return "dp";
        }
        return "storyboard";
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer intValue(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static OffsetDateTime maxTime(OffsetDateTime... values) {
        OffsetDateTime latest = null;
        for (OffsetDateTime value : values) {
            if (value == null) {
                continue;
            }
            if (latest == null || value.isAfter(latest)) {
                latest = value;
            }
        }
        return latest;
    }

    private record ProjectRow(
            UUID projectId,
            UUID scriptId,
            UUID storyIdeaId,
            String title,
            String status,
            Integer durationSeconds,
            String screenType,
            Integer totalShots,
            OffsetDateTime updatedAt
    ) {
    }

    private record AssetRef(UUID assetId, String url, OffsetDateTime updatedAt) {
    }

    private static final class AssetGroup {
        private UUID storyboardAssetId;
        private String storyboardUrl;
        private UUID lightingAssetId;
        private String lightingUrl;
        private UUID cameraPlanAssetId;
        private String cameraPlanUrl;
        private OffsetDateTime updatedAt;

        UUID storyboardAssetId() {
            return storyboardAssetId;
        }

        String storyboardUrl() {
            return storyboardUrl;
        }

        UUID lightingAssetId() {
            return lightingAssetId;
        }

        String lightingUrl() {
            return lightingUrl;
        }

        UUID cameraPlanAssetId() {
            return cameraPlanAssetId;
        }

        String cameraPlanUrl() {
            return cameraPlanUrl;
        }

        OffsetDateTime updatedAt() {
            return updatedAt;
        }
    }
}
