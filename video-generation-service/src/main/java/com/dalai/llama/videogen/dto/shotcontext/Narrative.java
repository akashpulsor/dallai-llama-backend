package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.EmotionalArcPosition;

public record Narrative(
        String scriptLine,
        String screenplaySlug,
        EmotionalArcPosition arcPosition
) {
}
