package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.CampaignDataRequestDto;
import org.springframework.boot.crm.dto.CampaignDataResponseDto;

import java.util.List;

public interface CampaignManager {

    CampaignDataResponseDto add(CampaignDataRequestDto campaignDataRequestDto);


    CampaignDataResponseDto get(int campaignId, int businessId);

    List<CampaignDataResponseDto> getByBusinessId(int customerId);
}
