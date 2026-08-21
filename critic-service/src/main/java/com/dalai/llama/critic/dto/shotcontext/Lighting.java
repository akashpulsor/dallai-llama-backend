package com.dalai.llama.critic.dto.shotcontext;

import com.dalai.llama.critic.domain.MoodProfile;

public record Lighting(
        String keyLightNote,
        MoodProfile mood
) {
}
