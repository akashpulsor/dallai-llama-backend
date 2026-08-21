package com.dalai.llama.preprod.service.videogen.shotcontext;

import com.dalai.llama.preprod.domain.TimeOfDay;

public record Environment(
        String location,
        TimeOfDay timeOfDay,
        String weather,
        String environmentalEffects
) {
}
