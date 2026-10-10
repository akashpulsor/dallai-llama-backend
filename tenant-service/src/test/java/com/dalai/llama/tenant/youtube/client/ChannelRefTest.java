package com.dalai.llama.tenant.youtube.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelRefTest {

    private static final String ID = "UCabcdefghijklmnopqrstuv";

    @Test
    void parsesEveryShapeACreatorMightPaste() {
        assertThat(ChannelRef.parse("https://www.youtube.com/@RiyaMotion")).isEqualTo(new ChannelRef.ByHandle("@RiyaMotion"));
        assertThat(ChannelRef.parse("youtube.com/@riya.motion/videos")).isEqualTo(new ChannelRef.ByHandle("@riya.motion"));
        assertThat(ChannelRef.parse("https://m.youtube.com/@riya_motion?si=x")).isEqualTo(new ChannelRef.ByHandle("@riya_motion"));
        assertThat(ChannelRef.parse("@RiyaMotion")).isEqualTo(new ChannelRef.ByHandle("@RiyaMotion"));
        assertThat(ChannelRef.parse("RiyaMotion")).isEqualTo(new ChannelRef.ByHandle("@RiyaMotion"));
        assertThat(ChannelRef.parse("https://www.youtube.com/channel/" + ID)).isEqualTo(new ChannelRef.ById(ID));
        assertThat(ChannelRef.parse("  " + ID + " ")).isEqualTo(new ChannelRef.ById(ID));
        assertThat(ChannelRef.parse("https://www.youtube.com/user/riyamotion")).isEqualTo(new ChannelRef.ByUsername("riyamotion"));
    }

    @Test
    void rejectsLinksThatAreNotChannels() {
        assertThatThrownBy(() -> ChannelRef.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChannelRef.parse("https://vimeo.com/@riya")).hasMessageContaining("youtube.com");
        assertThatThrownBy(() -> ChannelRef.parse("https://www.youtube.com/watch?v=abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChannelRef.parse("https://www.youtube.com/c/Custom")).isInstanceOf(IllegalArgumentException.class);
    }
}
