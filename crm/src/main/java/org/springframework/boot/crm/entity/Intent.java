package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "intent")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Intent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "intent_id")
    private Integer intentId;

    @Column(name = "intent_name", nullable = false)
    private String intentName;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    // Self-referencing relationship for root intent
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "root_intent_id")
    private Intent rootIntent;

    // One-to-Many for child intents
    @OneToMany(mappedBy = "rootIntent",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY)
    private List<Intent> childIntents = new ArrayList<>();

    // Many-to-One relationship with PortalConfiguration
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portal_id", nullable = false)
    private PortalConfiguration portalConfiguration;

    // Helper methods
    public void addChildIntent(Intent childIntent) {
        if(childIntents==null){
            childIntents = new ArrayList<>();
        }
        childIntents.add(childIntent);
        childIntent.setRootIntent(this);
    }
}
