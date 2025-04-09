package org.springframework.boot.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DashBoardDataDto {

    private long totalCall;

    private double totalCost;

    private long totalToken;

    private long totalLeads;

    private int totalCampaigns;


}
