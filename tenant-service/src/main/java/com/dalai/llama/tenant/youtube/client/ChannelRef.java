package com.dalai.llama.tenant.youtube.client;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** How a creator pointed at their channel. Parsed from whatever they paste: a channel URL, an
 * {@code @handle} URL, a bare {@code @handle}, a {@code UC...} id or a legacy {@code /user/} URL. */
public sealed interface ChannelRef {

    record ById(String channelId) implements ChannelRef {}

    record ByHandle(String handle) implements ChannelRef {}

    record ByUsername(String username) implements ChannelRef {}

    Pattern CHANNEL_ID = Pattern.compile("^UC[A-Za-z0-9_-]{22}$");
    Pattern HANDLE = Pattern.compile("^@?[A-Za-z0-9._-]{3,30}$");

    /** @throws IllegalArgumentException with a message the creator can act on */
    static ChannelRef parse(String input) {
        String raw = input == null ? "" : input.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Paste your YouTube channel link or @handle");
        if (CHANNEL_ID.matcher(raw).matches()) return new ById(raw);
        if (!raw.contains("/") && HANDLE.matcher(raw).matches()) return new ByHandle(withAt(raw));

        String path = path(raw);
        String[] parts = path.split("/");
        String first = parts.length > 1 ? parts[1] : "";
        String second = parts.length > 2 ? parts[2] : "";
        if (first.startsWith("@") && HANDLE.matcher(first).matches()) return new ByHandle(first);
        if (first.equals("channel") && CHANNEL_ID.matcher(second).matches()) return new ById(second);
        if (first.equals("user") && !second.isEmpty()) return new ByUsername(second);
        throw new IllegalArgumentException(
                "That doesn't look like a channel link. Use the link that starts with youtube.com/@ or youtube.com/channel/");
    }

    private static String withAt(String handle) {
        return handle.startsWith("@") ? handle : "@" + handle;
    }

    private static String path(String raw) {
        String url = raw.toLowerCase(Locale.ROOT).startsWith("http") ? raw : "https://" + raw;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That doesn't look like a link");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) {
            throw new IllegalArgumentException("Use a youtube.com channel link");
        }
        return uri.getPath() == null ? "" : uri.getPath();
    }
}
