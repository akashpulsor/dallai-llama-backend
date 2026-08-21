package com.dalai.llama.preprod.service.generation;

/** LLM output for a closed-vocabulary field occasionally drifts from the exact enum spelling
 * (wrong case, a synonym) -- this maps it back to the real domain enum with a fallback default,
 * so one malformed field never fails the whole shot-list batch. */
public final class TolerantEnumParser {

    private TolerantEnumParser() {
    }

    public static <T extends Enum<T>> T parse(Class<T> enumType, String rawValue, T fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        String normalized = rawValue.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        for (T candidate : enumType.getEnumConstants()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        return fallback;
    }
}
