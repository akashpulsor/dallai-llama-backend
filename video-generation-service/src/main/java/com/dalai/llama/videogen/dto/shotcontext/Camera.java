package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.ShotSize;

public record Camera(
        ShotSize shotSize,
        String cameraNote
) {
}
