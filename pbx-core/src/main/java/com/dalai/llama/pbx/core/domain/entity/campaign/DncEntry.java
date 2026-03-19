package com.dalai.llama.pbx.core.domain.entity.campaign;


import com.dalai.llama.pbx.core.domain.enums.DncSource;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Do-Not-Call list entry.
 * Checked by CallAuthorizationService (outbound) and DialerEngine.
 * expires_at = null means permanent.
 */
@Entity
@Table(name = "dnc_entries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DncEntry {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(length = 100)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private DncSource source;

    @Column(name = "added_at")
    private Instant addedAt;

    /** null = permanent DNC */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @PrePersist
    void prePersist() { addedAt = Instant.now(); }
}