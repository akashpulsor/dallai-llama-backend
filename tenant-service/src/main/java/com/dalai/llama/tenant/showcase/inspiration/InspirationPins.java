package com.dalai.llama.tenant.showcase.inspiration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pinterest pin links: the numeric pin id from a pasted link, and the canonical link back. */
final class InspirationPins {

    /** pinterest.com (any country domain, with or without www) /pin/<digits>, or the bare id. */
    private static final Pattern PIN = Pattern.compile(
            "^(?:https?://(?:[a-z]{2,3}\\.|www\\.)?pinterest\\.[a-z.]{2,6}/pin/)?([0-9]{6,32})/?(?:[?#].*)?$");

    private InspirationPins() {
    }

    static String idFrom(String link) {
        Matcher m = PIN.matcher(link == null ? "" : link.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Paste a Pinterest pin link like https://www.pinterest.com/pin/123456789/ "
                    + "(open a pin.it short link first to get it)");
        }
        return m.group(1);
    }

    static String url(String pinId) {
        return "https://www.pinterest.com/pin/" + pinId + "/";
    }
}
