package com.dalai.llama.tenant.showcase.domain;

public enum OfficialUploadStatus {
    QUEUED,
    UPLOADING,
    DONE,
    FAILED,
    /** YouTube made the upload private or refused API uploads; ops uploads it in Studio and links it. */
    NEEDS_MANUAL
}
