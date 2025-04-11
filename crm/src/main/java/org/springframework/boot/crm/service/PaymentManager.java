package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.PaymentData;

import java.time.LocalDate;
import java.util.Date;

public interface PaymentManager {

    void startCall(StartCallEvent startCallEvent);


    void stopCall(StopCallEvent stopCallEvent);

    PaymentDataDto getCallCharges(int callId);

    //create method which will return the charges data by campaign id
    PaymentDataDto getCallChargesByCampaignId(int campaignId);

    //Create method which will return the charges data by campaign run id
    PaymentDataDto getCallChargesByCampaignRunId(int campaignRunId);

    //Create method which will return the charges data by business id
    PaymentDataDto getCallChargesByBusinessId(int businessId);

    void getCallChargesByCampaignRunId();

    void addBillingInformation(BillingDataEvent billingDataEvent);

    PaymentDataDto getCallChargesByBusinessIdBetweenStartTimeAndEndTime(int businessId, LocalDate startTime, LocalDate endTime);



}
