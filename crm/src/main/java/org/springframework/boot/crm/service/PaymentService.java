package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.TokenAggregatesDto;
import org.springframework.boot.crm.entity.PaymentData;
import org.springframework.boot.crm.exceptions.PaymentDataNotFoundException;
import org.springframework.boot.crm.repository.PaymentDataRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Date;

@Service
public class PaymentService {

    private final PaymentDataRepository paymentDataRepository;


    public PaymentService(PaymentDataRepository paymentDataRepository) {
        this.paymentDataRepository = paymentDataRepository;
    }


    public PaymentData addPaymentData(PaymentData paymentData) {
        return this.paymentDataRepository.save(paymentData);
    }

    public PaymentData getPaymentDataByCallId(int callId) {
        return this.paymentDataRepository.findByCallId(callId).orElseThrow(()
        -> new PaymentDataNotFoundException("Payment data not found"));
    }



    public TokenAggregatesDto findAggregatesByBusinessId( int businessId) {
        return this.paymentDataRepository.findAggregatesByBusinessId(businessId);
    }

    public TokenAggregatesDto findAggregatesByCampaignId(int campaignId) {
        return this.paymentDataRepository.findAggregatesByCampaignId(campaignId);
    }


    public TokenAggregatesDto findAggregatesByCampaignRunId(int campaignRunId) {
        return this.paymentDataRepository.findAggregatesByCampaignRunId(campaignRunId);
    }

    public TokenAggregatesDto findAggregatesByBusinessIdBetweenStartTimeAndEndTime(int businessId, LocalDate startTime, LocalDate endTime) {
        return this.paymentDataRepository.findAggregatesByBusinessIdBetweenStartTimeAndEndTime(businessId, startTime, endTime);
    }


}
