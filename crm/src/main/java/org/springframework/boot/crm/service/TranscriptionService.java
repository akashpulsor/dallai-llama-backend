package org.springframework.boot.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.TranscriptionDto;
import org.springframework.boot.crm.entity.TranscriptionData;
import org.springframework.boot.crm.repository.TranscriptionDataRepository;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TranscriptionService {

    private final TranscriptionDataRepository transcriptionDataRepository;

    public TranscriptionService(TranscriptionDataRepository transcriptionDataRepository) {
        this.transcriptionDataRepository = transcriptionDataRepository; // Initialize with actual repository
    }


    public TranscriptionData add(TranscriptionDto transcriptionDto) {
        TranscriptionData transcriptionData = dtoToModel(transcriptionDto);
        return this.transcriptionDataRepository.save(transcriptionData);
    }


    public TranscriptionData getTranscriptionData(int businessId, int campaignRunId, int callId) {
        return this.transcriptionDataRepository.findByBusinessIdAndCampaignRunIdAndCallId(businessId, campaignRunId, callId);
    }

    public TranscriptionData getTranscriptionDataByCallId(int callId) {
        return this.transcriptionDataRepository.findByCallId(callId);
    }

    private TranscriptionData dtoToModel(TranscriptionDto transcriptionDto) {
        // Convert DTO to Model
        TranscriptionData transcriptionData = new TranscriptionData();
        transcriptionData.setAccountSid(transcriptionDto.getRecording().getAccountSid());
        transcriptionData.setCampaignId(transcriptionDto.getCampaignRunData().getCampaignId());
        transcriptionData.setCampaignRunId(transcriptionDto.getCampaignRunData().getCampaignRunId());
        transcriptionData.setBusinessId(transcriptionDto.getCampaignRunData().getBusinessId());
        transcriptionData.setPhoneId(transcriptionDto.getCampaignRunData().getPhoneId());
        transcriptionData.setLlmId(transcriptionDto.getCampaignRunData().getLlmId());
        transcriptionData.setCallId(transcriptionDto.getCallLog().getCallLogId());
        transcriptionData.setApiVersion(transcriptionDto.getRecording().getApiVersion());
        transcriptionData.setCallSid(transcriptionDto.getRecording().getCallSid());
        transcriptionData.setConferenceSid(transcriptionDto.getRecording().getConferenceSid());
        transcriptionData.setDateCreated(transcriptionDto.getRecording().getDateCreated());
        transcriptionData.setDateUpdated(transcriptionDto.getRecording().getDateUpdated());
        transcriptionData.setStartTime(transcriptionDto.getRecording().getStartTime());
        transcriptionData.setChannels(transcriptionDto.getRecording().getChannels());
        transcriptionData.setDuration(transcriptionDto.getRecording().getDuration());
        transcriptionData.setSid(transcriptionDto.getRecording().getSid());
        transcriptionData.setPrice(transcriptionDto.getRecording().getPrice());
        transcriptionData.setCallSid(transcriptionDto.getRecording().getCallSid());
        transcriptionData.setPriceUnit(transcriptionDto.getRecording().getPriceUnit());
        transcriptionData.setStatus(transcriptionDto.getRecording().getStatus().toString());
        transcriptionData.setSource(transcriptionDto.getRecording().getSource().toString());
        transcriptionData.setErrorCode(transcriptionDto.getRecording().getErrorCode());
        transcriptionData.setUri(transcriptionDto.getRecording().getUri());
        transcriptionData.setMediaUrl(transcriptionDto.getRecording().getMediaUrl().toString());
        transcriptionData.setCallId(transcriptionDto.getCallLog().getCallLogId());
        transcriptionData.setInboundTranscriptionText(transcriptionDto.getInboundData());
        transcriptionData.setOutboundTranscriptionText(transcriptionDto.getOutboundData());
        transcriptionData.setLeadId(transcriptionDto.getCallLog().getLeadId());
        return transcriptionData;
    }

}