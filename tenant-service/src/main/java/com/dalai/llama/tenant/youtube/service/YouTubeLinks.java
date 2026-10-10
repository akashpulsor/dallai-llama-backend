package com.dalai.llama.tenant.youtube.service;

import java.math.BigDecimal;

/** Links back to YouTube, which the developer policies require next to every player. */
public final class YouTubeLinks {

    /** Vertical videos up to this length open in YouTube's Shorts player. */
    private static final int SHORTS_MAX_SECONDS = 180;

    private YouTubeLinks() {
    }

    public static String channel(String channelId) {
        return "https://www.youtube.com/channel/" + channelId;
    }

    public static String watch(String videoId, boolean vertical, BigDecimal durationSeconds) {
        boolean shorts = vertical && durationSeconds != null && durationSeconds.intValue() <= SHORTS_MAX_SECONDS;
        return shorts ? "https://www.youtube.com/shorts/" + videoId : "https://www.youtube.com/watch?v=" + videoId;
    }
}
