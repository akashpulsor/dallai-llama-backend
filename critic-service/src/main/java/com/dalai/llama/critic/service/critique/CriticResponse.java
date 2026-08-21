package com.dalai.llama.critic.service.critique;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CriticResponse(
        List<CriticFindingItem> findings
) {
}
