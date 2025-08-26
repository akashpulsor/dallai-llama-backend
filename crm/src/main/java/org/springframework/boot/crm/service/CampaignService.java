package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.boot.crm.entity.InboundCampaignData;
import org.springframework.boot.crm.exceptions.CampaignNotFoundException;
import org.springframework.boot.crm.repository.CampaignDataRepository;
import org.springframework.boot.crm.repository.InboundCampaignDataRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CampaignService {

    private final CampaignDataRepository campaignDataRepository;

    private final InboundCampaignDataRepository inboundCampaignDataRepository;

    public CampaignService(CampaignDataRepository campaignDataRepository,
                           InboundCampaignDataRepository inboundCampaignDataRepository) {
        this.campaignDataRepository = campaignDataRepository;
        this.inboundCampaignDataRepository = inboundCampaignDataRepository;
    }

    public CampaignData save(CampaignData campaignData) {
        return campaignDataRepository.save(campaignData);
    }

    public InboundCampaignData save(InboundCampaignData inboundCampaignData) {
        return inboundCampaignDataRepository.save(inboundCampaignData);
    }

    public CampaignData findByCampaignIdAndBusinessId(int campaignId, int businessId) {
        return campaignDataRepository.findByCampaignIdAndBusinessId(campaignId, businessId).orElseThrow(()->new CampaignNotFoundException("Campaign not found"));
    }

    public InboundCampaignData findByInBoundCampaignIdAndBusinessId(int campaignId, int businessId) {
        return this.inboundCampaignDataRepository.findByCampaignIdAndBusinessId(campaignId, businessId).orElseThrow(()->new CampaignNotFoundException("Campaign not found"));
    }

    public List<CampaignData> getAllByBusinessId(int businessId) {
        return campaignDataRepository.findByBusinessId(businessId);
    }

    public int totalCampaigns(int businessId, LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            LocalDateTime startDateTime = startDate.atStartOfDay();
            LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);
            return 0;//campaignDataRepository.countUniqueCampaignIdsByBusinessIdAndCreatedAtBetween(businessId, startDateTime, endDateTime);
        } else {
            LocalDateTime sixtyDaysAgo = LocalDateTime.now(java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata"))).minusDays(60);
            return 0;//campaignDataRepository.countUniqueCampaignIdsByBusinessIdAndCreatedAtAfter(businessId, sixtyDaysAgo);
        }
    }
}
