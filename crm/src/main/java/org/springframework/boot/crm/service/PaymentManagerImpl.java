package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.StartCallEvent;
import org.springframework.boot.crm.dto.StopCallEvent;
import org.springframework.boot.crm.dto.TwilioStartMessageDto;
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
        paymentData.setCallId(callLog.getCallLogId());
        this.paymentService.addPaymentData(paymentData);
    }

    @Override
    @EventListener
    public void stopCall(StopCallEvent stopCallEvent) {
        log.info("Call Stopped for Call id - {}", stopCallEvent);
        int callId =stopCallEvent.getCallId();
        CallLog callLog = this.callManager.getCallLog(callId);
        PaymentData paymentData = this.paymentService.getPaymentDataByCallId(callLog.getCallLogId());
        paymentData.setEndTime(new Date());
        int time =calculateCallTime(paymentData.getStartTime(),paymentData.getEndTime());
        paymentData.setCallTime(time);
        this.paymentService.addPaymentData(paymentData);
        log.info("Total Payment Data - {}",paymentData);
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
