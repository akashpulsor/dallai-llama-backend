package org.springframework.boot.crm.service;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.CallLog;
import org.springframework.boot.crm.entity.ChargesData;
import org.springframework.boot.crm.entity.PaymentData;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Slf4j
@Component
public class PaymentManagerImpl implements PaymentManager {

    private final CallManager callManager;

    private final PaymentService paymentService;

    private static final double INPUT_TEXT_PRICE_PER_MILLION = 5.00;
    private static final double INPUT_TEXT_CACHED_PRICE_PER_MILLION = 2.50;
    private static final double OUTPUT_TEXT_PRICE_PER_MILLION = 20.00;
    private static final double INPUT_AUDIO_PRICE_PER_MILLION = 40.00;
    private static final double INPUT_AUDIO_CACHED_PRICE_PER_MILLION = 2.50;
    private static final double OUTPUT_AUDIO_PRICE_PER_MILLION = 80.00;

    private final ChargesDataService chargesDataService;

    public PaymentManagerImpl(CallManager callManager, PaymentService paymentService,
                              ChargesDataService chargesDataService) {
        this.callManager = callManager;
        this.paymentService = paymentService;
        this.chargesDataService = chargesDataService;
    }

    @Override
    @EventListener
    public void startCall(StartCallEvent startCallEvent) {
        log.info("Call Started for Call id - {}", startCallEvent);
        TwilioStartMessageDto.CustomParameterDto customParameter = startCallEvent.getTwilioStartEventDto().getTwilioStartMediaMessage().getStart().getCustomParameters();
        CallLog callLog = this.callManager.getCallLog(customParameter.getCallType(), customParameter.getCampaignRunId(), customParameter.getLeadId());
        if (callLog == null) {
            log.error("Call Log not found for Call id - {}", startCallEvent);
            return;
        }
        PaymentData paymentData = new PaymentData();
        paymentData.setCallId(callLog.getCallLogId());
        //Add setter for business id
        paymentData.setBusinessId(startCallEvent.getBusinessId());
        paymentData.setLeadId(startCallEvent.getLeadId());
        paymentData.setCampaignId(startCallEvent.getCampaignId());
        paymentData.setCampaignRunId(startCallEvent.getCampaignRunId());

        paymentData.setAgentId(startCallEvent.getAgentId());
        paymentData.setLlmId(startCallEvent.getLlmId());
        paymentData.setPhoneId(startCallEvent.getPhoneId());
        paymentData.setStartTime(LocalDate.now());
        this.paymentService.addPaymentData(paymentData);
    }

    @Override
    @EventListener
    public void stopCall(StopCallEvent stopCallEvent) {
        log.info("Call Stopped for Call id - {}", stopCallEvent);
        int callId = stopCallEvent.getCallId();
        PaymentData paymentData = this.paymentService.getPaymentDataByCallId(callId);
        paymentData.setEndTime(LocalDate.now());
        long time = calculateCallTime(paymentData.getStartTime(), paymentData.getEndTime());
        paymentData.setCallTime((int)time);
        this.paymentService.addPaymentData(paymentData);
        ChargesData chargesData = saveChargesData(paymentData);
        log.info("Total Payment Data - {} - {}", paymentData, chargesData);
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



    private long calculateCallTime(LocalDate startTime, LocalDate endTime) {
        if (startTime != null && endTime != null) {
            return endTime.atStartOfDay().toEpochSecond(ZoneOffset.UTC) -
                    startTime.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        }
        log.error("Total Call Time is zero");
        return 0;
    }


    @Override
    @Transactional
    public PaymentDataDto getCallCharges(int callId) {
        PaymentData paymentData = this.paymentService.getPaymentDataByCallId(callId);
        ChargesData chargesData = this.chargesDataService.getChargesDataByCallId(callId);
        if (chargesData == null || paymentData == null) {
            log.error("Call data not found for call id - {}", callId);
            return new PaymentDataDto(callId);
        }
        return  new PaymentDataDto(callId,
                chargesData.getTotalCharges(),
                chargesData.getServiceCharges(),
                chargesData.getModelCharges(),
                chargesData.getCarrierCharges(),
                chargesData.getEffectiveCost(),
                paymentData.getInputToken(),
                paymentData.getOutputToken(),
                paymentData.getTotalToken(),
                paymentData.getCallTime(),
                paymentData.getInputTextToken(),
                paymentData.getInputAudioToken(),
                paymentData.getInputCachedToken(),
                paymentData.getInputCachedTextToken(),
                paymentData.getInputCachedAudioToken(),
                paymentData.getOutputTextToken(),
                paymentData.getOutputAudioToken());
    }

    //create method which will return the charges data by campaign id
    @Override
    @Transactional
    public PaymentDataDto getCallChargesByCampaignId(int campaignId) {
        ChargesSummaryDto chargeSummaryDto = this.chargesDataService.getChargesSummaryByCampaignId(campaignId);
        TokenAggregatesDto tokenAggregatesDto = this.paymentService.findAggregatesByCampaignId(campaignId);
        return new PaymentDataDto(campaignId,
                chargeSummaryDto.getTotalCharges(),
                chargeSummaryDto.getTotalServiceCharges(),
                chargeSummaryDto.getTotalModelCharges(),
                chargeSummaryDto.getTotalCarrierCharges(),
                chargeSummaryDto.getTotalEffectiveCost(),
                tokenAggregatesDto.getTotalInputToken(),
                tokenAggregatesDto.getTotalOutputToken(),
                tokenAggregatesDto.getTotalToken(),
                tokenAggregatesDto.getTotalCallTime(),
                tokenAggregatesDto.getTotalInputTextToken(),
                tokenAggregatesDto.getTotalInputAudioToken(),
                tokenAggregatesDto.getTotalInputCachedToken(),
                tokenAggregatesDto.getTotalInputCachedTextToken(),
                tokenAggregatesDto.getTotalInputCachedAudioToken(),
                tokenAggregatesDto.getTotalOutputTextToken(),
                tokenAggregatesDto.getTotalOutputAudioToken());

    }

    //Create method which will return the charges data by campaign run id
    @Override
    @Transactional
    public PaymentDataDto getCallChargesByCampaignRunId(int campaignRunId) {
        ChargesSummaryDto chargeSummaryDto = this.chargesDataService.getChargesSummaryByCampaignRunId(campaignRunId);
        TokenAggregatesDto tokenAggregatesDto = this.paymentService.findAggregatesByCampaignRunId(campaignRunId);

        return new PaymentDataDto(campaignRunId,
                chargeSummaryDto.getTotalCharges(),
                chargeSummaryDto.getTotalServiceCharges(),
                chargeSummaryDto.getTotalModelCharges(),
                chargeSummaryDto.getTotalCarrierCharges(),
                chargeSummaryDto.getTotalEffectiveCost(),
                tokenAggregatesDto.getTotalInputToken(),
                tokenAggregatesDto.getTotalOutputToken(),
                tokenAggregatesDto.getTotalToken(),
                tokenAggregatesDto.getTotalCallTime(),
                tokenAggregatesDto.getTotalInputTextToken(),
                tokenAggregatesDto.getTotalInputAudioToken(),
                tokenAggregatesDto.getTotalInputCachedToken(),
                tokenAggregatesDto.getTotalInputCachedTextToken(),
                tokenAggregatesDto.getTotalInputCachedAudioToken(),
                tokenAggregatesDto.getTotalOutputTextToken(),
                tokenAggregatesDto.getTotalOutputAudioToken());
    }

    //Get charges summary by business id between start time and end time
    @Override
    @Transactional
    public PaymentDataDto getCallChargesByBusinessIdBetweenStartTimeAndEndTime(int businessId, LocalDate startTime, LocalDate endTime) {
        ChargesSummaryDto chargeSummaryDto = this.chargesDataService.getChargesSummaryByBusinessIdBetweenStartTimeAndEndTime(businessId, startTime, endTime);
        TokenAggregatesDto tokenAggregatesDto = this.paymentService.findAggregatesByBusinessIdBetweenStartTimeAndEndTime(businessId, startTime, endTime);
        if(chargeSummaryDto!=null && tokenAggregatesDto!= null){
            return new PaymentDataDto(businessId,
                    chargeSummaryDto.getTotalCharges(),
                    chargeSummaryDto.getTotalServiceCharges(),
                    chargeSummaryDto.getTotalModelCharges(),
                    chargeSummaryDto.getTotalCarrierCharges(),
                    chargeSummaryDto.getTotalEffectiveCost(),
                    tokenAggregatesDto.getTotalInputToken(),
                    tokenAggregatesDto.getTotalOutputToken(),
                    tokenAggregatesDto.getTotalToken(),
                    tokenAggregatesDto.getTotalCallTime(),
                    tokenAggregatesDto.getTotalInputTextToken(),
                    tokenAggregatesDto.getTotalInputAudioToken(),
                    tokenAggregatesDto.getTotalInputCachedToken(),
                    tokenAggregatesDto.getTotalInputCachedTextToken(),
                    tokenAggregatesDto.getTotalInputCachedAudioToken(),
                    tokenAggregatesDto.getTotalOutputTextToken(),
                    tokenAggregatesDto.getTotalOutputAudioToken());
        }
        return new PaymentDataDto(businessId);
    }


    //Create method which will return the charges data by business id
    @Override
    @Transactional
    public PaymentDataDto getCallChargesByBusinessId(int businessId) {
        ChargesSummaryDto chargeSummaryDto = this.chargesDataService.getChargesSummaryByBusinessId(businessId);
        TokenAggregatesDto tokenAggregatesDto = this.paymentService.findAggregatesByBusinessId(businessId);
        return   new PaymentDataDto(businessId,
                chargeSummaryDto.getTotalCharges(),
                chargeSummaryDto.getTotalServiceCharges(),
                chargeSummaryDto.getTotalModelCharges(),
                chargeSummaryDto.getTotalCarrierCharges(),
                chargeSummaryDto.getTotalEffectiveCost(),
                tokenAggregatesDto.getTotalInputToken(),
                tokenAggregatesDto.getTotalOutputToken(),
                tokenAggregatesDto.getTotalToken(),
                tokenAggregatesDto.getTotalCallTime(),
                tokenAggregatesDto.getTotalInputTextToken(),
                tokenAggregatesDto.getTotalInputAudioToken(),
                tokenAggregatesDto.getTotalInputCachedToken(),
                tokenAggregatesDto.getTotalInputCachedTextToken(),
                tokenAggregatesDto.getTotalInputCachedAudioToken(),
                tokenAggregatesDto.getTotalOutputTextToken(),
                tokenAggregatesDto.getTotalOutputAudioToken());
    }

    @Override
    public void getCallChargesByCampaignRunId() {

    }

    //TODO make it dynaminc for type of model and carrier 1 is for Twilio, Model Name
    public ChargesData saveChargesData(PaymentData paymentData) {
        ChargesData chargesData = new ChargesData();
        chargesData.setCallId(paymentData.getCallId());
        chargesData.setCampaignId(paymentData.getCampaignId());
        chargesData.setCampaignRunId(paymentData.getCampaignRunId());
        chargesData.setLeadId(paymentData.getLeadId());
        chargesData.setBusinessId(paymentData.getBusinessId());
        chargesData.setAgentId(paymentData.getAgentId());
        chargesData.setLlmId(paymentData.getLlmId());
        chargesData.setCallDuration(paymentData.getCallTime());
        chargesData.setCallStartTime(paymentData.getStartTime());
        chargesData.setCallEndTime(paymentData.getEndTime());
        chargesData.setCallDuration(paymentData.getCallTime());
        chargesData = calculatePrice(paymentData, chargesData);
        chargesData.setCarrierCharges(paymentData.getCallTime() * 0.0003);
        chargesData.setServiceCharges(chargesData.getModelCharges() * 0.20);
        chargesData.setCarrierId(1);
        chargesData.setModelName("gpt-4o-realtime-preview-2024-10-01");
        chargesData.setTotalCharges(chargesData.getModelCharges() + chargesData.getCarrierCharges() + chargesData.getServiceCharges());
        return this.chargesDataService.addChargesData(chargesData);

    }
    private ChargesData calculatePrice(PaymentData usageData, ChargesData chargesData) {
        long nonCachedInputTextTokens = usageData.getInputTextToken() - usageData.getInputCachedTextToken();
        long effectiveOutputTextTokens = usageData.getOutputTextToken();
        long nonCachedInputAudioTokens = usageData.getInputAudioToken() - usageData.getInputCachedAudioToken();
        long effectiveOutputAudioTokens = usageData.getOutputAudioToken();

        double inputTextCost = (double) usageData.getInputTextToken() / 1_000_000 * INPUT_TEXT_PRICE_PER_MILLION;
        chargesData.setInputTextCost(inputTextCost);
        double outputTextCost = (double) usageData.getOutputTextToken() / 1_000_000 * OUTPUT_TEXT_PRICE_PER_MILLION;
        chargesData.setOutputTextCost(outputTextCost);
        double inputAudioCost = (double) usageData.getInputAudioToken() / 1_000_000 * INPUT_AUDIO_PRICE_PER_MILLION;
        chargesData.setInputAudioCost(inputAudioCost);
        double outputAudioCost = (double) usageData.getOutputAudioToken() / 1_000_000 * OUTPUT_AUDIO_PRICE_PER_MILLION;
        chargesData.setOutputAudioCost(outputAudioCost);
        double inputTextCachedCost = (double) usageData.getInputCachedTextToken() / 1_000_000 * INPUT_TEXT_CACHED_PRICE_PER_MILLION;
        chargesData.setInputTextCachedCost(inputTextCachedCost);
        double inputAudioCachedCost = (double) usageData.getInputCachedAudioToken() / 1_000_000 * INPUT_AUDIO_CACHED_PRICE_PER_MILLION;
        chargesData.setInputAudioCachedCost(inputAudioCachedCost);
        double totalCost = inputTextCost + outputTextCost + inputAudioCost + outputAudioCost;
        chargesData.setModelCharges(totalCost);
        double effectiveCost = (double) nonCachedInputTextTokens / 1_000_000 * INPUT_TEXT_PRICE_PER_MILLION +
                (double) effectiveOutputTextTokens / 1_000_000 * OUTPUT_TEXT_PRICE_PER_MILLION +
                (double) nonCachedInputAudioTokens / 1_000_000 * INPUT_AUDIO_PRICE_PER_MILLION +
                (double) usageData.getInputCachedTextToken() / 1_000_000 * INPUT_TEXT_CACHED_PRICE_PER_MILLION +
                (double) usageData.getInputCachedAudioToken() / 1_000_000 * INPUT_AUDIO_CACHED_PRICE_PER_MILLION +
                (double) effectiveOutputAudioTokens / 1_000_000 * OUTPUT_AUDIO_PRICE_PER_MILLION;
        chargesData.setEffectiveCost(effectiveCost);
        return chargesData;
    }

}
