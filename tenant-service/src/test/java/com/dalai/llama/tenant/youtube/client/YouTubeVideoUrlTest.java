package com.dalai.llama.tenant.youtube.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeVideoUrlTest {

    @Test
    void readsTheIdFromEveryLinkShape() {
        assertThat(YouTubeVideoUrl.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s")).contains("dQw4w9WgXcQ");
        assertThat(YouTubeVideoUrl.videoId("youtube.com/watch?feature=share&v=dQw4w9WgXcQ")).contains("dQw4w9WgXcQ");
        assertThat(YouTubeVideoUrl.videoId("https://youtu.be/dQw4w9WgXcQ?si=abc")).contains("dQw4w9WgXcQ");
        assertThat(YouTubeVideoUrl.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ")).contains("dQw4w9WgXcQ");
        assertThat(YouTubeVideoUrl.videoId("https://m.youtube.com/embed/dQw4w9WgXcQ")).contains("dQw4w9WgXcQ");
        assertThat(YouTubeVideoUrl.videoId(" dQw4w9WgXcQ ")).contains("dQw4w9WgXcQ");
    }

    @Test
    void rejectsAnythingElse() {
        assertThat(YouTubeVideoUrl.videoId(null)).isEmpty();
        assertThat(YouTubeVideoUrl.videoId("https://vimeo.com/123456789")).isEmpty();
        assertThat(YouTubeVideoUrl.videoId("https://www.youtube.com/@RiyaMotion")).isEmpty();
        assertThat(YouTubeVideoUrl.videoId("https://www.youtube.com/watch?v=short")).isEmpty();
        assertThat(YouTubeVideoUrl.videoId("not a link at all")).isEmpty();
    }
}
