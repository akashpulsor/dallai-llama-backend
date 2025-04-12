package org.springframework.boot.crm.service;

import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.boot.crm.exceptions.CampaignRunNotFoundExceptions;
import org.springframework.boot.crm.repository.CampaignRunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
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

    public List<Integer> getLeadListByCampaignRunId(int campaignRunId) {
        return this.campaignRunRepository.getLeadList(campaignRunId);
    }

    public CampaignRunData addLeads(CampaignRunData campaignRunData, List<Integer> leads) {
        Set<Integer> leadSet = new HashSet<>(leads);
        campaignRunData.setLeads(leadSet);
        return addCampaignRun(campaignRunData);
    }

    //Create method to get list of leads id in paginated way
    public Page<Integer> getPaginatedLeadList(int campaignRunId, Pageable pageable) {
        return this.campaignRunRepository.getPaginatedLeadList(campaignRunId, pageable);
    }

    public CampaignRunData addLeads(CampaignRunData campaignRunData, Set<Integer> leads) {
        campaignRunData.setLeads(leads);
        return addCampaignRun(campaignRunData);
    }


    public CampaignRunData  getCampaignRunData(int campaignRunId, int businessId){
        return this.campaignRunRepository.findByCampaignRunIdAndBusinessId(campaignRunId, businessId).orElseThrow(
                () -> new CampaignRunNotFoundExceptions("Campaign run not found")
        );
    }

    public Page<CampaignRunData> getCampaignRunsByBusinessAndCampaign(
            Integer businessId,
            Integer campaignId,
            Pageable pageable) {
        return campaignRunRepository.findByBusinessIdAndCampaignId(businessId, campaignId, pageable);
    }

    public  CampaignRunData getDataByCampaignRunId(int campaignRunId) {
        return this.campaignRunRepository.findById(campaignRunId).orElseThrow(
                () -> new CampaignRunNotFoundExceptions("Campaign run not found")
        );
    }


}
