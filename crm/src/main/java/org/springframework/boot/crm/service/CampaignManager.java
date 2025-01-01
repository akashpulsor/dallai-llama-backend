package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.CampaignDataRequestDto;
import org.springframework.boot.crm.dto.CampaignDataResponseDto;
import org.springframework.boot.crm.dto.CampaignStartRequestDto;
import org.springframework.boot.crm.dto.CampaignStartResponseDto;

import java.util.List;

public interface CampaignManager {

    CampaignDataResponseDto add(CampaignDataRequestDto campaignDataRequestDto);


    CampaignDataResponseDto get(int campaignId, int businessId);

    List<CampaignDataResponseDto> getByBusinessId(int customerId);

    CampaignStartResponseDto start(CampaignStartRequestDto campaignStartRequestDto);

    void runCampaign(int campaignRunId, int BusinessId);
}
