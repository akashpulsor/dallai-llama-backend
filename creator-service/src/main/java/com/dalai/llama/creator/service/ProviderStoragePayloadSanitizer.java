package com.dalai.llama.creator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's provider-storage payload sanitization cluster - redacting
 * credential-shaped keys and omitting large base64/binary blobs before a run's Map is persisted or
 * returned, so the stored/response payload doesn't balloon with (or leak) raw provider bytes.
 * Unlike VideoProviderCatalog and friends, this needs one real collaborator - the injected
 * ObjectMapper, used to round-trip the sanitized structure into a plain LinkedHashMap - so it's
 * passed directly to the constructor rather than reached through an owner back-reference, the same
 * lightweight pattern as SceneViewMapper/RunViewMapper's static methods.
 */
final class ProviderStoragePayloadSanitizer {

    private final ObjectMapper objectMapper;

    ProviderStoragePayloadSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, Object> sanitizeProviderStorageMap(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(sanitizeProviderStoragePayload(payload, ""), new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private Object sanitizeProviderStoragePayload(Object value, String key) {
        String normalizedKey = defaultString(key, "").toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (normalizedKey.contains("authorization")
                || normalizedKey.contains("apikey")
                || normalizedKey.contains("secret")
                || normalizedKey.contains("token")) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            rawMap.forEach((rawKey, rawValue) -> {
                String childKey = stringValue(rawKey, "");
                sanitized.put(childKey, sanitizeProviderStoragePayload(rawValue, childKey));
            });
            return sanitized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : collection) {
                sanitized.add(sanitizeProviderStoragePayload(item, key));
            }
            return sanitized;
        }
        if (value instanceof byte[] bytes) {
            return "[binary bytes=" + bytes.length + " stored_in_object_storage]";
        }
        if (value instanceof String text) {
            if (isBase64StoragePayloadKey(normalizedKey) && text.length() > 80) {
                return "[base64 chars=" + text.length() + " omitted_stored_in_object_storage]";
            }
            int dataUrlMarker = text.indexOf(";base64,");
            if (dataUrlMarker > 0 && text.length() > dataUrlMarker + 80) {
                return "[data-url chars=" + text.length() + " omitted_stored_in_object_storage]";
            }
            if (text.length() > 20000 && looksLikeBase64(text)) {
                return "[base64-like chars=" + text.length() + " omitted_stored_in_object_storage]";
            }
            if (text.length() > 40000) {
                return truncate(text, 40000);
            }
        }
        return value;
    }

    private boolean isBase64StoragePayloadKey(String normalizedKey) {
        return normalizedKey.equals("data")
                || normalizedKey.equals("audiocontent")
                || normalizedKey.equals("audio")
                || normalizedKey.equals("imagebytes")
                || normalizedKey.equals("bytesbase64encoded")
                || normalizedKey.equals("b64json")
                || normalizedKey.equals("base64")
                || normalizedKey.endsWith("base64");
    }

    private boolean looksLikeBase64(String value) {
        if (value == null || value.length() < 512) {
            return false;
        }
        int checked = 0;
        int valid = 0;
        int max = Math.min(value.length(), 4096);
        for (int index = 0; index < max; index++) {
            char ch = value.charAt(index);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            checked++;
            if ((ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '+'
                    || ch == '/'
                    || ch == '=') {
                valid++;
            }
        }
        return checked > 0 && valid >= Math.max(1, checked * 98 / 100);
    }
}
