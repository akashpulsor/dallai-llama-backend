package com.dalai.llama.preprod.service.videogen.shotcontext;

import com.dalai.llama.preprod.domain.MoodProfile;

public record Lighting(
        String keyLightNote,
        MoodProfile mood
) {
}
