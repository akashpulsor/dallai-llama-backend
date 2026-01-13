package com.dalai.llama.pbx.core.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "call_analytics")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallAnalytics {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String tenantId;
    private String callId;

    // Conversational AI analysis
    @Lob
    @Column(columnDefinition = "TEXT")
    private String transcriptCaller;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String transcriptAgent;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String summary;

    private String dominantLanguage;
    private String sentimentCaller;
    private String sentimentAgent;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String intentsJson;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String entitiesJson;

    private Boolean compliant;
    private String complianceReasons;

    // AI processing metadata
    private Instant processedAt = Instant.now();
}
