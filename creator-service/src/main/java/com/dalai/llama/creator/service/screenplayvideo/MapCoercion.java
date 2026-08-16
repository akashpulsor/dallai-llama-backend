package com.dalai.llama.creator.service.screenplayvideo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generic map/type-coercion utility belt, extracted out of ScreenplayVideoService (previously
 * ~27 instance methods reached via package-private access, called from nearly every method in
 * the class). Pure functions, no request/tenant state - safe as static methods.
 *
 * Callers use {@code import static ...MapCoercion.*;} so existing unqualified call sites
 * (firstText(...), mapValue(...), etc.) keep compiling unchanged after the instance methods are
 * removed from ScreenplayVideoService.
 *
 * copyMap()/toJson() need an ObjectMapper for structural Map&lt;-&gt;Map conversion and JSON
 * serialization; a private static default instance is used rather than threading the app's
 * configured bean through every call site - safe here because these two operations don't touch
 * app-specific serialization concerns (date formatting, custom modules), just generic structural
 * copying of already-parsed Map/List/String/Number values.
 */
public final class MapCoercion {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MapCoercion() {
    }

    public static Map<String, Object> copyMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return OBJECT_MAPPER.convertValue(map, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        }
        return new LinkedHashMap<>();
    }

    public static Map<String, Object> mapValue(Object value) {
        return copyMap(value);
    }

    public static List<Map<String, Object>> mapListValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = copyMap(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    public static List<Object> firstList(Object... values) {
        for (Object value : values) {
            if (value instanceof List<?> list) {
                return new ArrayList<>(list);
            }
        }
        return List.of();
    }

    public static Map<String, Object> firstMap(Object... values) {
        for (Object value : values) {
            Map<String, Object> map = copyMap(value);
            if (!map.isEmpty()) {
                return map;
            }
        }
        return new LinkedHashMap<>();
    }

    public static Map<String, Object> firstNonEmptyMap(Object... values) {
        return firstMap(values);
    }

    public static Object firstValue(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static Object firstNonNull(Object... values) {
        return firstValue(values);
    }

    public static String firstText(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    public static void putIfBlank(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null || String.valueOf(value).isBlank()) {
            return;
        }
        if (firstText(target.get(key)).isBlank()) {
            target.put(key, value);
        }
    }

    public static BigDecimal firstBigDecimal(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            BigDecimal parsed = decimalValue(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    public static BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim().replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static BigDecimal positiveMoney(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    public static String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    public static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public static int positiveInt(Object value, int fallback) {
        int parsed = intValue(value, fallback);
        return parsed <= 0 ? fallback : parsed;
    }

    public static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).replace("s", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static long deterministicSeed(String... parts) {
        int hash = java.util.Objects.hash((Object[]) parts);
        long value = Integer.toUnsignedLong(hash);
        return value == 0 ? 1 : value;
    }

    public static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static UUID uuidValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static String envString(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
    }

    public static String normalizeWhitespace(String value) {
        return defaultString(value, "").replaceAll("\\s+", " ").trim();
    }
}
