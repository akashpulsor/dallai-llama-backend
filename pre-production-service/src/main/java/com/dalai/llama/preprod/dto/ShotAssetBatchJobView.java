package com.dalai.llama.preprod.dto;

import java.util.UUID;

public record ShotAssetBatchJobView(
        UUID jobId,
        String status,
        int totalShots,
        int completedShots,
        int errorCount,
        String currentStepLabel,
        String lastError
) {
}
