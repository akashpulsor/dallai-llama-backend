package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.boot.crm.exceptions.CampaignNotFoundException;
import org.springframework.boot.crm.repository.CampaignDataRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CampaignService {

    private final CampaignDataRepository campaignDataRepository;

    public CampaignService(CampaignDataRepository campaignDataRepository) {
        this.campaignDataRepository = campaignDataRepository;
    }

    public CampaignData save(CampaignData campaignData) {
        return campaignDataRepository.save(campaignData);
    }

    public CampaignData findByCampaignIdAndBusinessId(int campaignId, int businessId) {
        return campaignDataRepository.findByCampaignIdAndBusinessId(campaignId, businessId).orElseThrow(()->new CampaignNotFoundException("Campaign not found"));
    }

    public List<CampaignData> getAllByBusinessId(int businessId) {
        return campaignDataRepository.findByBusinessId(businessId);
    }
}
