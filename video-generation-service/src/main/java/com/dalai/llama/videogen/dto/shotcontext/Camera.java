package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.ShotSize;

public record Camera(
        ShotSize shotSize,
        String cameraNote,
        /** The CAMERA_PLAN-kind ShotImage the camera-plan step produced (MinIO location) --
         * attached as a CAMERA_PLAN_IMAGE ShotPromptReference. Nullable. */
        String cameraPlanImageBucket,
        String cameraPlanImageObjectKey
) {
}
