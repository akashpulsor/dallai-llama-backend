package com.dalai.llama.blobmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Event published whenever a recording is uploaded by blob-manager.
 * Sent to Kafka topic: cdr.recording.{tenantId}
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordingEvent {

    private String tenantId;     // tenant namespace
    private String callId;       // call identifier
    private String recordUrl;    // stored object URL
    private Instant timestamp;   // event creation timestamp

}
