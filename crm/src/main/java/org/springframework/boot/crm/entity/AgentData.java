package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@Entity(name = "agent_data")
@EntityListeners(AuditingEntityListener.class)
public class AgentData {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="agent_id")
    private int agentId;

    @Column(name="business_id")
    private int businessId;

    @Column(name="agent_name")
    private String agentName;

    @Column(name="persona")
    private String persona;

    @Column(name="role")
    private String role;

    @Column(name="voice")
    private String voice;

    @Column(name="active")
    private boolean active;


    @Embedded
    private SanitaryColumn sanitaryColumn;
}
