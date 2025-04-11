package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.ChargesSummaryDto;
import org.springframework.boot.crm.entity.ChargesData;
import org.springframework.stereotype.Service;
import org.springframework.boot.crm.repository.ChargesDataRepository;

import java.time.LocalDate;
import java.util.Date;

@Service
public class ChargesDataService {


    private final ChargesDataRepository chargesDataRepository;

    public ChargesDataService (ChargesDataRepository chargesDataRepository) {
        this.chargesDataRepository = chargesDataRepository;
    }


    public ChargesData addChargesData(ChargesData chargesData) {
        return this.chargesDataRepository.save(chargesData);
    }

    public ChargesData getChargesDataByCallId(int callId) {
        return this.chargesDataRepository.findByCallId(callId);
    }


    public ChargesSummaryDto getChargesSummaryByCampaignId(int campaignId) {
        return this.chargesDataRepository.findChargesSummaryByCampaignId(campaignId);
    }

    public ChargesSummaryDto getChargesSummaryByCampaignRunId(int campaignRunId) {
        return this.chargesDataRepository.findChargesSummaryByCampaignRunId(campaignRunId);
    }

    public ChargesSummaryDto getChargesSummaryByBusinessId(int businessId) {
        return this.chargesDataRepository.findChargesSummaryByBusinessId(businessId);
    }

    //get charges summary by business id between start time and end time
    public ChargesSummaryDto getChargesSummaryByBusinessIdBetweenStartTimeAndEndTime(int businessId, LocalDate startTime, LocalDate endTime) {
        return this.chargesDataRepository.findChargesSummaryByBusinessIdBetweenStartTimeAndEndTime(businessId, startTime, endTime);
    }

}
