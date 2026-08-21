package com.dalai.llama.critic.domain.entity;

import com.dalai.llama.critic.domain.CriticRole;
import com.dalai.llama.critic.domain.CritiqueSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** OBSERVATION -> RISK -> CAUSE -> CORRECTION, one row per finding a role critic raised. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "critique_finding")
public class CritiqueFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 24)
    private CriticRole role;

    @Column(name = "observation", nullable = false, columnDefinition = "text")
    private String observation;

    @Column(name = "risk", columnDefinition = "text")
    private String risk;

    @Column(name = "cause", columnDefinition = "text")
    private String cause;

    @Column(name = "correction", columnDefinition = "text")
    private String correction;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 8)
    private CritiqueSeverity severity;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
