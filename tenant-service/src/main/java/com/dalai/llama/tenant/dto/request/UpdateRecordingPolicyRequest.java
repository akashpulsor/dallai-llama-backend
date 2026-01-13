package com.dalai.llama.tenant.dto.request;

import com.dalai.llama.tenant.domain.entity.enums.RecordingMode;

public record UpdateRecordingPolicyRequest(

        RecordingMode mode,

        Boolean pauseAllowed,
        Boolean stereoRecording,
        Boolean transcriptionEnabled,
        String transcriptionLanguage,
        Boolean sentimentAnalysisEnabled,
        String storageLocation
) {}
