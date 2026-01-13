package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "call_billing_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallBillingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String tenantId;

    /** FK to call_records.callId (not to trunk) */
    private String callId;

    /** duration extracted from call record */
    private Double durationSec;

    /** applied rate */
    private Double ratePerMinute;

    /** final billed amount */
    private Double billedAmount;

    /** INBOUND or OUTBOUND */
    private Boolean inbound;
    private Boolean outbound;

    private Instant createdAt = Instant.now();
}
