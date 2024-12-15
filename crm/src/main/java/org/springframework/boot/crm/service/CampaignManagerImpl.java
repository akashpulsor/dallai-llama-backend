package org.springframework.boot.crm.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.boot.crm.entity.CampaignRunData;
import org.springframework.boot.crm.entity.LeadData;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class CampaignManagerImpl implements  CampaignManager {
    private final CampaignService campaignService;

    private final CampaignRunService campaignRunService;

    private final BusinessManager businessManager;

    private final LeadManager leadManager;

    private final CallManager callManager;

    private static final int BATCH_SIZE = 1000;

    public CampaignManagerImpl(CampaignService campaignService,
                               BusinessManager businessManager,
                               LeadManager leadManager,
                               CampaignRunService campaignRunService, CallManager callManager) {
        this.campaignService = campaignService;
        this.businessManager = businessManager;
        this.leadManager = leadManager;
        this.campaignRunService = campaignRunService;
        this.callManager = callManager;
    }
        @Override
    public CampaignDataResponseDto add(CampaignDataRequestDto campaignDataRequestDto) {
        this.businessManager.getBusinessData(campaignDataRequestDto.getBusinessId());
        CampaignData campaignData = this.campaignService.save(dtoToModel(campaignDataRequestDto));
        return modelToDto(campaignData);
    }

    @Override
    public CampaignDataResponseDto get(int campaignId, int businessId) {
        this.businessManager.getBusinessData(businessId);
        CampaignData campaignData = this.campaignService.findByCampaignIdAndBusinessId(campaignId, businessId);
        return modelToDto(campaignData);
    }

    @Override
    public List<CampaignDataResponseDto> getByBusinessId(int  businessId) {
        this.businessManager.getBusinessData(businessId);
        return this.campaignService.getAllByBusinessId(businessId).stream().
                map(this::modelToDto).collect(Collectors.toList());
    }

    @Override
    public CampaignStartResponseDto start(CampaignStartRequestDto campaignStartRequestDto) {
        get(campaignStartRequestDto.getCampaignId(), campaignStartRequestDto.getBusinessId());
        CampaignRunData campaignRunData = dtoToModel(campaignStartRequestDto);
        campaignRunData.setStatus(CampaignRunEnum.STARTED);
        this.campaignRunService.addCampaignRun(campaignRunData);
        if ( campaignRunData.isAll() ){
            startAll(campaignRunData);
            campaignRunData.setStatus(CampaignRunEnum.LEADS_COPIED);
        }
        else {
             campaignRunData = this.campaignRunService.addLeads(campaignStartRequestDto.getBusinessId(),
                     campaignStartRequestDto.getLeadList());
             campaignRunData.setStatus(CampaignRunEnum.LEADS_COPIED);

        }
        campaignRunData = this.campaignRunService.addCampaignRun(campaignRunData);
        return modelToDto(campaignRunData);
    }


    public CampaignStartResponseDto run(CampaignStartRequestDto campaignStartRequestDto) {

        return new CampaignStartResponseDto();
    }

        public void startAll(CampaignRunData campaignRunData) {
        Stream<LeadData> leadDataStream = this.leadManager.getLeadDataByStream(campaignRunData.getBusinessId());
        try (leadDataStream){
            Set<Integer> batchLeads = new HashSet<>(BATCH_SIZE);
            AtomicInteger counter = new AtomicInteger(0);
            leadDataStream.forEach(leadId -> {
                batchLeads.add(leadId.getLeadId());

                // When batch size is reached, save and clear
                if (counter.incrementAndGet() % BATCH_SIZE == 0) {
                    this.campaignRunService.saveBatchOfLeads(campaignRunData.getCampaignRunId(), new HashSet<>(batchLeads));
                    batchLeads.clear();
                }
            });
        }
    }

    private CampaignData dtoToModel(CampaignDataRequestDto campaignDataRequestDto){
        CampaignData campaignData = new CampaignData();
        campaignData.setCampaignId(campaignDataRequestDto.getCampaignId());
        campaignData.setBusinessId(campaignDataRequestDto.getBusinessId());
        if(!StringUtils.isBlank(campaignDataRequestDto.getCampaignAim())) campaignData.setCampaignAim(campaignDataRequestDto.getCampaignAim());
        if(!StringUtils.isBlank(campaignDataRequestDto.getCampaignDesc())) campaignData.setCampaignDesc(campaignDataRequestDto.getCampaignDesc());
        if(!StringUtils.isBlank(campaignDataRequestDto.getCampaignImgUrl())) campaignData.setCampaignImgUrl(campaignDataRequestDto.getCampaignImgUrl());
        if(!StringUtils.isBlank(campaignDataRequestDto.getLanguage())) campaignData.setLanguage(campaignDataRequestDto.getLanguage());
        if(!StringUtils.isBlank(campaignDataRequestDto.getCampaignName())) campaignData.setCampaignName(campaignDataRequestDto.getCampaignName());
        if(!StringUtils.isBlank(campaignDataRequestDto.getConversationGuideLines())) campaignData.setConversationGuideLines(campaignDataRequestDto.getConversationGuideLines());
        if(!StringUtils.isBlank(campaignDataRequestDto.getFirstMessage())) campaignData.setFirstMessage(campaignDataRequestDto.getFirstMessage());
        if(!StringUtils.isBlank(campaignDataRequestDto.getHandlingFaq())) campaignData.setHandlingFaq(campaignDataRequestDto.getHandlingFaq());
        if(!StringUtils.isBlank(campaignDataRequestDto.getPlacingOrder()))campaignData.setPlacingOrder(campaignDataRequestDto.getPlacingOrder());
        if(campaignDataRequestDto.getDuration() > 0 )campaignData.setDuration(campaignDataRequestDto.getDuration());
        campaignData.setActive(campaignDataRequestDto.isActive());
        return campaignData;
    }

    private CampaignStartResponseDto modelToDto(CampaignRunData campaignRunData){
        CampaignStartResponseDto campaignStartResponseDto = new CampaignStartResponseDto();
        campaignStartResponseDto.setCampaignRunId(campaignRunData.getBusinessId());
        return campaignStartResponseDto;
    }

    private CampaignRunData dtoToModel(CampaignStartRequestDto CampaignStartRequestDto){
        CampaignRunData campaignRunData = new CampaignRunData();
        campaignRunData.setCampaignId(CampaignStartRequestDto.getCampaignId());
        campaignRunData.setBusinessId(CampaignStartRequestDto.getBusinessId());
        campaignRunData.setAll(CampaignStartRequestDto.isAll());
        campaignRunData.setAgentId(CampaignStartRequestDto.getAgentId());
        campaignRunData.setLanguage(CampaignStartRequestDto.getLanguage());
        campaignRunData.setLlmId(CampaignStartRequestDto.getLlmId());
        campaignRunData.setPhoneId(CampaignStartRequestDto.getPhoneId());
        return campaignRunData;
    }

    private CampaignDataResponseDto modelToDto(CampaignData campaignData){
        CampaignDataResponseDto campaignDataResponseDto = new CampaignDataResponseDto();
        campaignDataResponseDto.setBusinessId(campaignData.getBusinessId());
        campaignDataResponseDto.setCampaignId(campaignData.getCampaignId());
        if(!StringUtils.isBlank(campaignData.getCampaignAim())) campaignDataResponseDto.setCampaignAim(campaignData.getCampaignAim());
        if(!StringUtils.isBlank(campaignData.getCampaignDesc())) campaignDataResponseDto.setCampaignDesc(campaignData.getCampaignDesc());
        if(!StringUtils.isBlank(campaignData.getCampaignImgUrl())) campaignDataResponseDto.setCampaignImgUrl(campaignData.getCampaignImgUrl());
        if(!StringUtils.isBlank(campaignData.getCampaignName())) campaignDataResponseDto.setCampaignName(campaignData.getCampaignName());
        if(!StringUtils.isBlank(campaignData.getCampaignPrompt())) campaignDataResponseDto.setCampaignPrompt(campaignData.getCampaignPrompt());
        if(!StringUtils.isBlank(campaignData.getConversationGuideLines())) campaignDataResponseDto.setConversationGuideLines(campaignData.getConversationGuideLines());
        if(!StringUtils.isBlank(campaignData.getFirstMessage())) campaignDataResponseDto.setFirstMessage(campaignData.getFirstMessage());
        if(!StringUtils.isBlank(campaignData.getHandlingFaq())) campaignDataResponseDto.setHandlingFaq(campaignData.getHandlingFaq());
        if(!StringUtils.isBlank(campaignData.getPlacingOrder())) campaignDataResponseDto.setPlacingOrder(campaignData.getPlacingOrder());
        if(!StringUtils.isBlank(campaignData.getPlacingOrder())) campaignDataResponseDto.setLanguage(campaignData.getLanguage());
        campaignDataResponseDto.setActive(campaignData.isActive());
        campaignDataResponseDto.setDuration(campaignData.getDuration());
        return campaignDataResponseDto;
    }
}
