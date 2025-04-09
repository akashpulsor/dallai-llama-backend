package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.crm.dto.CampaignRunEnum;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Data
@Entity(name = "campaign_run_data")
public class CampaignRunData {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="campaign_run_id")
    private int campaignRunId;

    @Column(name="business_id")
    @NotNull(message = "Business Id is mandatory")
    private int businessId;

    @Column(name="campaign_id")
    @NotNull(message = "Campaign Id is mandatory")
    private int campaignId;

    @Column(name="`all`")
    private boolean all;


    @ElementCollection
    @CollectionTable(
            name = "campaign_run_leads",
            joinColumns = @JoinColumn(name = "campaign_run_id")
    )
    @Column(name = "lead_id")
    private Set<Integer> leads;


    @Column(name="agent_id")
    @NotNull(message = "Agent Id is mandatory")
    private int agentId;

    @Column(name="`language`")
    private String language;

    @Enumerated(EnumType.STRING)
    private CampaignRunEnum status;

    @Column(name="llm_id")
    @NotNull(message = "Llm Id is mandatory")
    private int llmId;

    @Column(name="phone_id")
    @NotNull(message = "Phone Id is mandatory")
    private int phoneId;

    @Column(name="call_sid")
    private String callSId;

    @CreatedDate
    @Column(name="created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name="updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if(createdAt==null){
            createdAt = LocalDateTime.now();
        }
        updatedAt= LocalDateTime.now();
    }
}
