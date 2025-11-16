package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "call_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String tenantId;
    private String callId;
    private String caller;
    private String callee;
    private String recordPath;

    private Double durationSec;
    private Double packetLoss;
    private Double jitter;
    private String status; // in-progress, completed, failed

    @Lob
    @Column(columnDefinition = "TEXT")
    private String transcriptCaller;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String transcriptResponder;

    private Instant startedAt;
    private Instant endedAt;
    private Instant createdAt = Instant.now();

    private Double billedAmount; // from billing service
}
