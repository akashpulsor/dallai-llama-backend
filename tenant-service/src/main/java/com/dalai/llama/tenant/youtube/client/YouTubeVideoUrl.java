package com.dalai.llama.tenant.youtube.client;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Pulls the video id out of whatever YouTube link a creator pastes: watch?v=, youtu.be/,
 * /shorts/, /embed/, /live/, or a bare id. */
public final class YouTubeVideoUrl {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private YouTubeVideoUrl() {
    }

    public static Optional<String> videoId(String input) {
        if (input == null) return Optional.empty();
        String raw = input.trim();
        if (ID.matcher(raw).matches()) return Optional.of(raw);
        URI uri;
        try {
            uri = URI.create(raw.toLowerCase(Locale.ROOT).startsWith("http") ? raw : "https://" + raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String candidate = null;
        if (host.equals("youtu.be")) {
            candidate = path.length() > 1 ? path.substring(1).split("/")[0] : null;
        } else if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
            String[] parts = path.split("/");
            if (path.equals("/watch")) {
                candidate = queryParam(uri.getRawQuery(), "v");
            } else if (parts.length > 2 && (parts[1].equals("shorts") || parts[1].equals("embed") || parts[1].equals("live"))) {
                candidate = parts[2];
            }
        }
        return candidate != null && ID.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }

    private static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }
}
