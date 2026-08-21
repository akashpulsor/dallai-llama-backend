package com.dalai.llama.videogen.dto;

import java.util.UUID;

public record ExportBundleView(
        UUID bundleId,
        String status,
        String objectKey
) {
}
