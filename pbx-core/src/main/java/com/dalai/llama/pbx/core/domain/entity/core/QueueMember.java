package com.dalai.llama.pbx.core.domain.entity.core;


import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "queue_members")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QueueMember {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_id", nullable = false)
    private Queue queue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @Builder.Default
    private Integer priority = 1;

    @Builder.Default
    private Integer penalty = 0;
}