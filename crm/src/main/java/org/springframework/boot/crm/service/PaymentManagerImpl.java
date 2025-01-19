package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.PaymentData;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
public class PaymentManagerImpl implements PaymentManager {

    private final CallManager callManager;

    private final PaymentService paymentService;

    public PaymentManagerImpl(CallManager callManager, PaymentService paymentService) {
        this.callManager = callManager;
        this.paymentService = paymentService;
    }

    @Override
    @EventListener
    public void startCall(StartCallEvent startCallEvent) {
        log.info("Call Started for Call id - {}", startCallEvent);
        TwilioStartMessageDto.CustomParameterDto customParameter = startCallEvent.getTwilioStartEventDto().getTwilioStartMediaMessage().getStart().getCustomParameters();
        CallLog callLog = this.callManager.getCallLog(customParameter.getCallType(), customParameter.getCampaignRunId(), customParameter.getLeadId());
        PaymentData paymentData = new PaymentData();
        paymentData.setStartTime(new Date());
        paymentData.setCallId(callLog.getCallLogId());
        this.paymentService.addPaymentData(paymentData);
    }

    @Override
    @EventListener
    public void stopCall(StopCallEvent stopCallEvent) {
        log.info("Call Stopped for Call id - {}", stopCallEvent);
        int callId =stopCallEvent.getCallId();
        PaymentData paymentData = this.paymentService.getPaymentDataByCallId(callId);
        paymentData.setEndTime(new Date());
        int time =calculateCallTime(paymentData.getStartTime(),paymentData.getEndTime());
        paymentData.setCallTime(time);
        this.paymentService.addPaymentData(paymentData);
        log.info("Total Payment Data - {}",paymentData);
    }

    @Override
    @EventListener
    public void addBillingInformation(BillingDataEvent billingDataEvent) {
        log.info("Adding token data for Call id - {}", billingDataEvent);
        int callId =billingDataEvent.getCallLogId();
        OpenAiResponseDoneDto.Usage usage = billingDataEvent.getUsage();
        PaymentData paymentData = this.paymentService.getPaymentDataByCallId(callId);
        paymentData = updateTokenInformation(paymentData,usage);
        log.info("updated Payment Data - {}",paymentData);
    }

    public PaymentData updateTokenInformation(PaymentData paymentData, OpenAiResponseDoneDto.Usage usage) {
        paymentData.setInputToken(usage.getInputTokens() + paymentData.getInputToken());
        paymentData.setOutputToken(usage.getOutputTokens() + paymentData.getOutputToken());
        paymentData.setTotalToken(usage.getTotalTokens() + paymentData.getTotalToken());
        paymentData.setInputTextToken(usage.getInputTokenDetails().getTextTokens() + paymentData.getInputTextToken());
        paymentData.setInputAudioToken(usage.getInputTokenDetails().getAudioTokens()+ paymentData.getInputAudioToken());
        paymentData.setInputCachedToken(usage.getInputTokenDetails().getCachedTokens() + paymentData.getInputCachedToken());
        paymentData.setInputCachedTextToken(usage.getInputTokenDetails().getCachedTokensDetails().getTextTokens() + paymentData.getInputCachedTextToken());
        paymentData.setInputCachedAudioToken(usage.getInputTokenDetails().getCachedTokensDetails().getAudioTokens() + paymentData.getInputCachedAudioToken());
        paymentData.setOutputTextToken(usage.getOutputTokenDetails().getTextTokens() + paymentData.getOutputTextToken());
        paymentData.setOutputAudioToken(usage.getOutputTokenDetails().getAudioTokens() + paymentData.getOutputTextToken());
        return this.paymentService.addPaymentData(paymentData);
    }



    public int calculateCallTime(Date startTime, Date endTime) {
        if (startTime != null && endTime != null) {
            // Get time in milliseconds and convert to seconds
            long diffInMillies = endTime.getTime() - startTime.getTime();
            return (int) (diffInMillies / 1000);
        }
        log.error("Total Call Time is zero");
        return 0;
    }


    @Override
    public PaymentData getCallCharges(int callId) {
        return this.paymentService.getPaymentDataByCallId(callId);
    }

    @Override
    public void getCallChargesByCampaignRunId() {

    }
}
