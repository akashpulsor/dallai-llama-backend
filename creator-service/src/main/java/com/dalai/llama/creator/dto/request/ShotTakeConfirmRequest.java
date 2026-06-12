package com.dalai.llama.creator.dto.request;

public record ShotTakeConfirmRequest(
        Boolean accepted,
        String note
) {
}
