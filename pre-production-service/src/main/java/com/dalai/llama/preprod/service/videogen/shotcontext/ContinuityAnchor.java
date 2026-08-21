package com.dalai.llama.preprod.service.videogen.shotcontext;

import com.dalai.llama.preprod.domain.AnchorType;

public record ContinuityAnchor(
        AnchorType anchorType,
        String subjectId,
        String description,
        String referenceObjectKey
) {
}
