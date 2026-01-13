package com.dalai.llama.tenant.dto.response;

import com.dalai.llama.tenant.domain.entity.enums.RecordingMode;
public record RecordingPolicyResponse(

        RecordingMode mode,
        boolean pauseAllowed,
        boolean stereoRecording,
        boolean transcriptionEnabled,
        String transcriptionLanguage,
        boolean sentimentAnalysisEnabled,
        String storageLocation
) {}
