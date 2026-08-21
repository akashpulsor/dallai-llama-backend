package com.dalai.llama.critic.dto.shotcontext;

import com.dalai.llama.critic.domain.TimeOfDay;

public record Environment(
        String location,
        TimeOfDay timeOfDay,
        String weather,
        String environmentalEffects
) {
}
