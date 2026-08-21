package com.dalai.llama.videogen.dto;

import java.util.List;

public record RejectRequest(
        String reason,
        List<String> issueTags
) {
}
