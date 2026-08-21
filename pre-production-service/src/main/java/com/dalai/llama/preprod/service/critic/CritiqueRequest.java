package com.dalai.llama.preprod.service.critic;

import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;

import java.util.UUID;

public record CritiqueRequest(
        UUID projectId,
        UUID shotId,
        ShotContext shotContext
) {
}
