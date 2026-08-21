package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.MoodProfile;

public record Lighting(
        String keyLightNote,
        MoodProfile mood
) {
}
