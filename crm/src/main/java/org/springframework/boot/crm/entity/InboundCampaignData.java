package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.crm.dto.CampaignRunEnum;

import java.util.Set;
@Data
@Entity
@DiscriminatorValue("IN_BOUND")
public class InboundCampaignData  extends CampaignData  {


    @Column(name="initial_message_recording")
    private String initialMessageRecording;



}
