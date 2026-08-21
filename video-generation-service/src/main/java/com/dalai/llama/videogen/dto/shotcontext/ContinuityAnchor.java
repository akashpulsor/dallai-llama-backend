package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.AnchorType;

public record ContinuityAnchor(
        AnchorType anchorType,
        String subjectId,
        String description,
        String referenceObjectKey
) {
}
