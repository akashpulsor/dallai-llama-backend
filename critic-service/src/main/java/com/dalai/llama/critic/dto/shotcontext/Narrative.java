package com.dalai.llama.critic.dto.shotcontext;

import com.dalai.llama.critic.domain.EmotionalArcPosition;

public record Narrative(
        String scriptLine,
        String screenplaySlug,
        EmotionalArcPosition arcPosition
) {
}
