package org.springframework.boot.crm.service;

import jakarta.persistence.Column;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class CampaignManagerImpl implements  CampaignManager {
    private final CampaignService campaignService;

    private final CampaignRunService campaignRunService;


    private final LeadManager leadManager;



    private static final int BATCH_SIZE = 1000;

    public CampaignManagerImpl(CampaignService campaignService,
                               LeadManager leadManager,
                               CampaignRunService campaignRunService) {
        this.campaignService = campaignService;
        this.leadManager = leadManager;
        this.campaignRunService = campaignRunService;
    }
    @Override
    public CampaignDataResponseDto add(CampaignDataRequestDto campaignDataRequestDto) {
        if("IN_BOUND".equals(campaignDataRequestDto.getCampaignType())){
            CampaignData campaignData = this.campaignService.save(InBoundDtoToModel(campaignDataRequestDto));
            return modelToDto(campaignData);
        }
        CampaignData campaignData = this.campaignService.save(dtoToModel(campaignDataRequestDto));
        return modelToDto(campaignData);
    }

    @Override
    public InboundCampaignData add(InboundCampaignData inboundCampaignData) {
        return this.campaignService.save(inboundCampaignData);
    }

    @Override
    public CampaignDataResponseDto get(int campaignId, int businessId) {
        CampaignData campaignData = this.campaignService.findByCampaignIdAndBusinessId(campaignId, businessId);
        return modelToDto(campaignData);
    }

    @Override
    public InboundCampaignData getInBoundCampaignData(int campaignId, int businessId) {
        InboundCampaignData campaignData = this.campaignService.findByInBoundCampaignIdAndBusinessId(campaignId, businessId);
        return campaignData;
    }

    public CampaignData getCampaignData(int campaignId, int businessId) {
        return this.campaignService.findByCampaignIdAndBusinessId(campaignId, businessId);
    }

    @Override
    public List<CampaignDataResponseDto> getByBusinessId(int  businessId) {
        return this.campaignService.getAllByBusinessId(businessId).stream().
                map(this::modelToDto).collect(Collectors.toList());
    }

    @Override
    public CampaignRunResponseDto start(CampaignStartRequestDto campaignStartRequestDto) {
        get(campaignStartRequestDto.getCampaignId(), campaignStartRequestDto.getBusinessId());
        CampaignRunData campaignRunData = dtoToModel(campaignStartRequestDto);
        campaignRunData.setStatus(CampaignRunEnum.STARTED);
        campaignRunData = this.campaignRunService.addCampaignRun(campaignRunData);
        if ( campaignRunData.isAll() ){
            startAll(campaignRunData);
            campaignRunData.setStatus(CampaignRunEnum.LEADS_COPIED);
        }
        else {
             campaignRunData = this.campaignRunService.addLeads(campaignRunData,
                     campaignStartRequestDto.getLeadList());
             campaignRunData.setStatus(CampaignRunEnum.LEADS_COPIED);

        }
        campaignRunData = this.campaignRunService.addCampaignRun(campaignRunData);
        return modelToDto(campaignRunData);
    }

    @Override
    public CampaignRunResponseDto startInboundCampaign(CampaignStartRequestDto campaignStartRequestDto) {
        InboundCampaignData inboundCampaignData = getInBoundCampaignData(campaignStartRequestDto.getCampaignId(), campaignStartRequestDto.getBusinessId());
        CampaignRunData campaignRunData = dtoToModel(campaignStartRequestDto);
        campaignRunData.setStatus(CampaignRunEnum.STARTED);
        campaignRunData = this.campaignRunService.addCampaignRun(campaignRunData);
        return modelToDto(campaignRunData);
    }

    /*
    * after saving while making campaign we will save the campaign without incoming url,
    * after then we will start the campaign with campaign run here while saving if we find that  campaign is
    * of campaign in bound we will update the  webhook url in inbound campaign data
    * */

    public int totalCampaigns(int businessId, LocalDate startDate, LocalDate endDate) {
            return this.campaignService.totalCampaigns(businessId,startDate,endDate);
    }

    @Override
    public Page<CampaignRunData> getCampaignRunsByBusinessAndCampaign(int businessId, int campaignId, Pageable pageable) {
        return this.campaignRunService.getCampaignRunsByBusinessAndCampaign(businessId, campaignId, pageable);
    }

    public CampaignRunData getCampaignRunData(int campaignRunId, int businessId) {
        return this.campaignRunService.getCampaignRunData(campaignRunId, businessId);
    }

    public CampaignRunData addCampaignRunData(CampaignRunData campaignRunData) {
        return this.campaignRunService.addCampaignRun(campaignRunData);
    }

    public List<Integer> getLeadListByCampaignRunId(int campaignRunId) {
        return this.campaignRunService.getLeadListByCampaignRunId(campaignRunId);
    }


    // Create method to get list of leads id in paginated way
    @Override
    public Page<Integer> getPaginatedLeadList(int campaignRunId, Pageable pageable) {
        return this.campaignRunService.getPaginatedLeadList(campaignRunId, pageable);
    }

    @Override
    public CampaignRunData getCampaignRunDataById(int campaignRunId) {
        return this.campaignRunService.getDataByCampaignRunId(campaignRunId);
    }

    @Transactional
    public void startAll(CampaignRunData campaignRunData) {
        Page<LeadResponseDto> leadDataPage = this.leadManager.getLeadsByBusinessIdPaginated(campaignRunData.getBusinessId(), 0, BATCH_SIZE, "leadId", false);
        while (leadDataPage.hasContent()) {
            List<Integer> leadIds = leadDataPage.getContent().stream().map(LeadResponseDto::getLeadId).toList();
            this.campaignRunService.saveBatchOfLeads(campaignRunData.getCampaignRunId(), new HashSet<>(leadIds));
            leadDataPage = this.leadManager.getLeadsByBusinessIdPaginated(campaignRunData.getBusinessId(), leadDataPage.getNumber() + 1, BATCH_SIZE, "leadId", false);
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

    private InboundCampaignData InBoundDtoToModel(CampaignDataRequestDto campaignDataRequestDto){
        InboundCampaignData campaignData = new InboundCampaignData();
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

        if(!StringUtils.isBlank(campaignDataRequestDto.getFirstMessage())) campaignData.setFirstMessage(campaignDataRequestDto.getFirstMessage());
        if(campaignDataRequestDto.getDuration() > 0 )campaignData.setDuration(campaignDataRequestDto.getDuration());
        campaignData.setActive(campaignDataRequestDto.isActive());
        if(!StringUtils.isBlank(campaignDataRequestDto.getPlacingOrder()))campaignData.setPlacingOrder(campaignDataRequestDto.getPlacingOrder());
        return campaignData;
    }

    private CampaignRunResponseDto modelToDto(CampaignRunData campaignRunData){
        CampaignRunResponseDto campaignRunResponseDto = new CampaignRunResponseDto();
        campaignRunResponseDto.setCampaignRunId(campaignRunData.getCampaignRunId());
        campaignRunResponseDto.setBusinessId(campaignRunData.getBusinessId());
        campaignRunResponseDto.setCampaignId(campaignRunData.getCampaignId());
        campaignRunResponseDto.setAll(campaignRunData.isAll());
        campaignRunResponseDto.setAgentId(campaignRunData.getAgentId());
        campaignRunResponseDto.setLanguage(campaignRunData.getLanguage());
        campaignRunResponseDto.setLlmId(campaignRunData.getLlmId());
        campaignRunResponseDto.setPhoneId(campaignRunData.getPhoneId());
        campaignRunResponseDto.setCallSId(campaignRunData.getCallSId());
        campaignRunResponseDto.setStatus(campaignRunData.getStatus());
        campaignRunResponseDto.setCreatedAt(campaignRunData.getCreatedAt());
        campaignRunResponseDto.setUpdatedAt(campaignRunData.getUpdatedAt());
        campaignRunResponseDto.setCallSId(campaignRunData.getCallSId());
        return campaignRunResponseDto;
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
