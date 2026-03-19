package com.dalai.llama.pbx.core.domain.entity.core;


import com.dalai.llama.pbx.core.domain.enums.QueueStrategy;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "queues",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "name"})
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Queue {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    @Builder.Default
    private QueueStrategy strategy = QueueStrategy.ROUND_ROBIN;

    @Column(name = "max_wait_seconds")
    @Builder.Default
    private Integer maxWaitSeconds = 300;

    @Column(name = "moh_file", length = 255)
    private String mohFile;

    @Column(name = "wrap_up_seconds")
    @Builder.Default
    private Integer wrapUpSeconds = 15;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}