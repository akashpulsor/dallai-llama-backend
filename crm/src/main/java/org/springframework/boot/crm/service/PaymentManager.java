package org.springframework.boot.crm.service;

import org.springframework.boot.crm.dto.BillingDataEvent;
import org.springframework.boot.crm.dto.StartCallEvent;
import org.springframework.boot.crm.dto.StopCallEvent;
import org.springframework.boot.crm.entity.PaymentData;

public interface PaymentManager {

    void startCall(StartCallEvent startCallEvent);


    void stopCall(StopCallEvent stopCallEvent);

    PaymentData getCallCharges(int callId);

    void getCallChargesByCampaignRunId();

    void addBillingInformation(BillingDataEvent billingDataEvent);



}
