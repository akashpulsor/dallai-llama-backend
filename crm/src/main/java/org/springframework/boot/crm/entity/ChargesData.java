package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.Date;

@Data
@Entity(name = "charges_data")
public class ChargesData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cost_id")
    private int costId;

    @Column(name = "call_id")
    private int callId;

    @Column(name = "campaign_run_id")
    private int campaignRunId;

    @Column(name = "campaign_id")
    private int campaignId;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "business_id")
    private int businessId;

    @Column(name = "lead_id")
    private int leadId;

    @Column(name = "agent_id")
    private int agentId;

    @Column(name = "llm_id")
    private int llmId;

    @Column(name = "phone_id")
    private int phoneId;

    @Column(name = "call_duration")
    private int callDuration;

    @Column(name = "call_last_status")
    private int callLastStatus;

    @Column(name = "call_start_time")
    private LocalDate callStartTime;

    @Column(name = "call_end_time")
    private LocalDate callEndTime;

    @Column(name = "carrier_id")
    private int carrierId;

    @Column(name = "carrier_charges")
    private double carrierCharges;

    @Column(name = "model_charges")
    private double modelCharges;

    @Column(name = "service_charges")
    private double serviceCharges;

    @Column(name = "total_charges")
    private double totalCharges;

    @Column(name = "input_text_cost")
    private double inputTextCost;

    @Column(name = "output_text_cost")
    private double outputTextCost;

    @Column(name = "input_audio_cost")
    private double inputAudioCost;

    @Column(name = "output_audio_cost")
    private double outputAudioCost;

    @Column(name = "input_text_cached_cost")
    private double inputTextCachedCost;

    @Column(name = "input_audio_cached_cost")
    private double inputAudioCachedCost;

    @Column(name = "total_cost")
    private double totalCost;

    @Column(name = "effective_cost")
    private double effectiveCost;
}
