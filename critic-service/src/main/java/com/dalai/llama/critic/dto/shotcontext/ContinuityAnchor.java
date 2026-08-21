package com.dalai.llama.critic.dto.shotcontext;

import com.dalai.llama.critic.domain.AnchorType;

public record ContinuityAnchor(
        AnchorType anchorType,
        String subjectId,
        String description,
        String referenceObjectKey
) {
}
