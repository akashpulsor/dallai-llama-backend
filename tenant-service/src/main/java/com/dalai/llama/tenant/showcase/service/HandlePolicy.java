package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Pure rules for what a handle may look like. Whether a valid handle is still free is a
 * database question and lives in {@link CreatorProfileService}. */
@Component
public class HandlePolicy {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 40;
    static final String FALLBACK_BASE = "creator";

    private static final Pattern VALID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$");
    private static final Pattern NOT_ALLOWED = Pattern.compile("[^a-z0-9]+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    public enum Problem { TOO_SHORT, TOO_LONG, INVALID_FORMAT, RESERVED }

    private final Set<String> reserved;

    public HandlePolicy(ShowcaseProperties properties) {
        this.reserved = properties.profile().reservedHandles().stream()
                .map(h -> h.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Lower-cases, drops accents, turns every run of other characters into one hyphen and trims
     * hyphens and length. "Akash Tripathi Films!" becomes "akash-tripathi-films". */
    public String normalize(String raw) {
        if (raw == null) return "";
        String ascii = COMBINING_MARKS.matcher(Normalizer.normalize(raw, Normalizer.Form.NFD)).replaceAll("");
        String slug = trimHyphens(NOT_ALLOWED.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-"));
        return slug.length() <= MAX_LENGTH ? slug : trimHyphens(slug.substring(0, MAX_LENGTH));
    }

    /** The starting point for an automatically generated handle. */
    public String baseFromName(String name) {
        String base = normalize(name);
        return problem(base).isEmpty() ? base : FALLBACK_BASE;
    }

    /** The {@code n}th candidate for a base (n = 1 is the base itself, then base-2, base-3 ...),
     * cut so the suffix always fits. */
    public String candidate(String base, int n) {
        if (n <= 1) return base;
        String suffix = "-" + n;
        String head = base.length() + suffix.length() <= MAX_LENGTH
                ? base
                : trimHyphens(base.substring(0, MAX_LENGTH - suffix.length()));
        return head + suffix;
    }

    public Optional<Problem> problem(String handle) {
        if (handle == null || handle.length() < MIN_LENGTH) return Optional.of(Problem.TOO_SHORT);
        if (handle.length() > MAX_LENGTH) return Optional.of(Problem.TOO_LONG);
        if (!VALID.matcher(handle).matches()) return Optional.of(Problem.INVALID_FORMAT);
        if (reserved.contains(handle)) return Optional.of(Problem.RESERVED);
        return Optional.empty();
    }

    private static String trimHyphens(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') start++;
        while (end > start && s.charAt(end - 1) == '-') end--;
        return s.substring(start, end);
    }
}
