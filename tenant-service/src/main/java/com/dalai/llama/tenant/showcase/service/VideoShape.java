package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;

/** A video's aspect ratio for the player box; 16:9 when YouTube didn't tell us. */
record VideoShape(int width, int height) {

    private static final VideoShape DEFAULT = new VideoShape(16, 9);

    static VideoShape of(YouTubeVideo video) {
        if (video == null || video.getAspectW() == null || video.getAspectH() == null) return DEFAULT;
        return new VideoShape(video.getAspectW(), video.getAspectH());
    }

    boolean vertical() {
        return height > width;
    }
}
