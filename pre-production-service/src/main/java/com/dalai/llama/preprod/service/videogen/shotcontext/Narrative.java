package com.dalai.llama.preprod.service.videogen.shotcontext;

import com.dalai.llama.preprod.domain.EmotionalArcPosition;

public record Narrative(
        String scriptLine,
        String screenplaySlug,
        EmotionalArcPosition arcPosition
) {
}
