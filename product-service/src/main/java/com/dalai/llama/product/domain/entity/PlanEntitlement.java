package com.dalai.llama.product.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plan_entitlements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanEntitlement {

    @Id
    private UUID id;

    @OneToOne
    @JoinColumn(name = "plan_id", unique = true)
    private Plan plan;

    private int maxAgents;
    private int maxSupervisors;
    private int maxConcurrentLogins;

    private int maxPstnChannels;
    private int maxDids;

    private boolean inboundEnabled;
    private boolean outboundEnabled;
    private boolean recordingEnabled;

    private boolean analyticsEnabled;
    private int analyticsRetentionDays;

    private boolean aiSttEnabled;
    private boolean aiLlmEnabled;
    private boolean aiBotEnabled;
    private boolean aiSentimentEnabled;
    private long aiTokensPerMonth;

    private int maxQueues;
    private int maxIvrFlows;

    private int recordingStorageGb;
    private int recordingRetentionDays;

    private boolean bargeEnabled;
    private boolean whisperEnabled;
    private boolean monitorEnabled;

    private boolean conferenceEnabled;
    private boolean voicemailEnabled;
    private boolean callbackEnabled;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;
}
