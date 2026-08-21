package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.TimeOfDay;

public record Environment(
        String location,
        TimeOfDay timeOfDay,
        String weather,
        String environmentalEffects
) {
}
