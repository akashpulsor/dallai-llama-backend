package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "call_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 36)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String callId;  // = SIP Call-ID (Kamailio)

    private String direction; // inbound / outbound

    private String fromNumber;
    private String toNumber;

    private Long agentId;
    private String agentUsername;
    private String entrypoint;

    private Instant startedAt;
    private Instant ringingAt;
    private Instant answeredAt;
    private Instant endedAt;

    private Double durationSec;

    private String status; // ringing, in-progress, completed, missed, failed

    // Recording file paths (written by media service)
    private String recordingMix;
    private String recordingCaller;
    private String recordingAgent;

    private Instant createdAt = Instant.now();
}
