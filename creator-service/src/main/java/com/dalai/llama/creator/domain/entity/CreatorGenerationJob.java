package com.dalai.llama.creator.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Mutability;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creator_generation_jobs")
public class CreatorGenerationJob {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "job_type", nullable = false, length = 64)
    private String jobType;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private Integer progress;

    @Column(name = "redis_key", length = 240)
    private String redisKey;

    @Column(name = "kafka_topic", length = 160)
    private String kafkaTopic;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "input_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputPayload = new LinkedHashMap<>();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ReplacementOnlyJsonMutabilityPlan.class)
    @Column(name = "output_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> outputPayload = new LinkedHashMap<>();

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (status == null || status.isBlank()) {
            status = "PENDING";
        }
        if (progress == null) {
            progress = 0;
        }
        if (inputPayload == null) {
            inputPayload = new LinkedHashMap<>();
        }
        if (outputPayload == null) {
            outputPayload = new LinkedHashMap<>();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        stripEmbeddedMedia();
    }

    @PreUpdate
    void preUpdate() {
        stripEmbeddedMedia();
    }

    /**
     * Reference images/video are already durable in object storage before a provider request is
     * ever built; any base64/data-URI that ends up in these JSONB columns is a byproduct of
     * assembling that provider call, not the source of truth. Strip it here, at the single point
     * every insert/update passes through, so no future call site can accidentally persist
     * megabytes of inline media into Postgres again.
     */
    private void stripEmbeddedMedia() {
        inputPayload = sanitizePayload(inputPayload);
        outputPayload = sanitizePayload(outputPayload);
    }

    private static final Set<String> BASE64_KEY_HINTS = Set.of(
            "data", "imagebytes", "audiocontent", "videocontent", "bytesbase64encoded", "b64json", "base64"
    );
    private static final int BASE64_KEY_HINT_MIN_CHARS = 80;
    private static final int BASE64_CONTENT_SNIFF_MIN_CHARS = 4000;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Object sanitized = sanitizeValue(payload, "");
        return sanitized instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private static Object sanitizeValue(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String childKey = String.valueOf(k);
                result.put(childKey, sanitizeValue(v, childKey));
            });
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            for (Object item : collection) {
                result.add(sanitizeValue(item, key));
            }
            return result;
        }
        if (value instanceof byte[] bytes) {
            return "[binary bytes=" + bytes.length + " omitted]";
        }
        if (value instanceof String text) {
            if (text.startsWith("data:") && text.indexOf(";base64,") > 0 && text.length() > BASE64_KEY_HINT_MIN_CHARS) {
                return "[data-url chars=" + text.length() + " omitted]";
            }
            String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            boolean keyHint = BASE64_KEY_HINTS.contains(normalizedKey) || normalizedKey.endsWith("base64");
            if (keyHint && text.length() > BASE64_KEY_HINT_MIN_CHARS) {
                return "[base64 chars=" + text.length() + " omitted]";
            }
            if (text.length() > BASE64_CONTENT_SNIFF_MIN_CHARS && looksLikeBase64(text)) {
                return "[base64-like chars=" + text.length() + " omitted]";
            }
        }
        return value;
    }

    private static boolean looksLikeBase64(String text) {
        int sampleLen = Math.min(200, text.length());
        for (int i = 0; i < sampleLen; i++) {
            char c = text.charAt(i);
            boolean valid = Character.isLetterOrDigit(c) || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r';
            if (!valid) {
                return false;
            }
        }
        return true;
    }
}
