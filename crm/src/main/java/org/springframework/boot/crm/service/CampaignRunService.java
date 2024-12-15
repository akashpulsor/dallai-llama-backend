package org.springframework.boot.crm.service;

import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.boot.crm.exceptions.CampaignRunNotFoundExceptions;
import org.springframework.boot.crm.repository.CampaignRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
@Transactional
public class CampaignRunService {


    private final CampaignRunRepository campaignRunRepository;

    public CampaignRunService(CampaignRunRepository campaignRunRepository) {
        this.campaignRunRepository = campaignRunRepository;
    }

    public void saveBatchOfLeads(int campaignRunId, Set<Integer> leads) {
        // Assuming you have a native query method to insert leads in batch
        campaignRunRepository.insertLeadsBatch(campaignRunId, leads);
    }

    public CampaignRunData addCampaignRun(CampaignRunData campaignRunData) {
        return this.campaignRunRepository.save(campaignRunData);
    }


    public CampaignRunData addLeads(int businessId, Set<Integer> leads) {
        CampaignRunData campaignRunData = this.campaignRunRepository.findByBusinessId(businessId).
                orElseThrow(() -> new CampaignRunNotFoundExceptions("Campaign Run Not Found"));
        campaignRunData.setLeads(leads);
        return addCampaignRun(campaignRunData);
    }

    public CampaignRunData addLeads(CampaignRunData campaignRunData, Set<Integer> leads) {
        campaignRunData.setLeads(leads);
        return addCampaignRun(campaignRunData);
    }


}
