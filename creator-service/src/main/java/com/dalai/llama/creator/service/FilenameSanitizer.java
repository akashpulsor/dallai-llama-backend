package com.dalai.llama.creator.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static com.dalai.llama.creator.service.screenplayvideo.MapCoercion.*;

/**
 * Extraction of ScreenplayVideoService's two small filename/URL string-sanitization helpers -
 * unrelated to each other in purpose but both trivial, pure text transforms with no
 * ScreenplayVideoService collaborators, so grouped into one small class rather than two
 * single-method files.
 */
final class FilenameSanitizer {

    String safeSlug(String value) {
        String normalized = defaultString(value, "scene")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "scene" : normalized;
    }

    String urlEncode(String value) {
        return URLEncoder.encode(defaultString(value, ""), StandardCharsets.UTF_8);
    }
}
